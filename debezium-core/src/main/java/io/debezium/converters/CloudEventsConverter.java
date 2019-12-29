/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.converters;

import static io.debezium.converters.SerializerType.withName;
import static org.apache.kafka.connect.data.Schema.Type.STRUCT;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Set;import java.util.concurrent.ConcurrentHashMap;

import io.debezium.data.Envelope;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.json.JsonConverterConfig;
import org.apache.kafka.connect.json.JsonDeserializer;
import org.apache.kafka.connect.json.JsonSerializer;
import org.apache.kafka.connect.storage.Converter;
import org.apache.kafka.connect.storage.ConverterConfig;
import org.apache.kafka.connect.storage.ConverterType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.confluent.connect.avro.AvroConverter;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.debezium.util.SchemaNameAdjuster;

/**
 * Implementation of Converter that express schemas and objects with CloudEvents specification. The serialization
 * format can be Json or Avro.
 *
 * The serialization format of CloudEvents is configured with
 * {@link CloudEventsConverterConfig#CLOUDEVENTS_SERIALIZER_TYPE_CONFIG cloudevents.serializer.type} option.
 *
 * The serialization format of the data attribute in CloudEvents is configured with
 * {@link CloudEventsConverterConfig#CLOUDEVENTS_DATA_SERIALIZER_TYPE_CONFIG cloudevents.data.serializer.type} option.
 *
 * If the serialization format of the data attribute in CloudEvents is JSON, it can be configured whether to enable
 * schemas with {@link CloudEventsConverterConfig#CLOUDEVENTS_JSON_SCHEMAS_ENABLE_CONFIG json.schemas.enable} option.
 *
 * If the the serialization format is Avro, the URL of schema registries for CloudEvents and its data attribute can be
 * configured with {@link CloudEventsConverterConfig#CLOUDEVENTS_SCHEMA_REGISTRY_URL_CONFIG schema.registry.url}.
 *
 * There are two modes for transferring CloudEvents as Kafka messages: structured and binary. In the structured content
 * mode, event metadata attributes and event data are placed into the Kafka message value section using an event format.
 * In the binary content mode, the value of the event data is placed into the Kafka message's value section as-is,
 * with the content-type header value declaring its media type; all other event attributes are mapped to the Kafka
 * message's header section.
 *
 * Since kafka converters has not support headers yet, right now CloudEvents converter use structured mode as the
 * default.
 */
public class CloudEventsConverter implements Converter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CloudEventsConverter.class);
    private static Method CONVERT_TO_JSON_METHOD;
    private static Method CONVERT_TO_CONNECT_METHOD;

    static {
        try {
            CONVERT_TO_JSON_METHOD = JsonConverter.class.getDeclaredMethod("convertToJson", Schema.class, Object.class);
            CONVERT_TO_CONNECT_METHOD = JsonConverter.class.getDeclaredMethod("convertToConnect", Schema.class, JsonNode.class);
            CONVERT_TO_JSON_METHOD.setAccessible(true);
            CONVERT_TO_CONNECT_METHOD.setAccessible(true);
        }
        catch (NoSuchMethodException e) {
            throw new DataException(e.getCause());
        }
    }

    private final JsonConverter jsonCEConverter = new JsonConverter();
    private final JsonSerializer jsonSerializer = new JsonSerializer();
    private final JsonDeserializer jsonDeserializer = new JsonDeserializer();

    private SchemaRegistryClient ceSchemaRegistry;
    private AvroConverter avroCEConverter = new AvroConverter();
    private AvroConverter avroDataConverter = new AvroConverter();

    private SerializerType ceSerializerType = withName(CloudEventsConverterConfig.CLOUDEVENTS_SERIALIZER_TYPE_DEFAULT);
    private SerializerType dataSerializerType = withName(CloudEventsConverterConfig.CLOUDEVENTS_DATA_SERIALIZER_TYPE_DEFAULT);
    private boolean enableJsonSchemas = CloudEventsConverterConfig.CLOUDEVENTS_JSON_SCHEMAS_ENABLE_DEFAULT;
    private List<String> schemaRegistryUrls;

    /**
     * Specify the schema registry client for CloudEvents when using Avro as its serialization format.
     *
     * @param schemaRegistry the schema registry client
     */
    public void setCESchemaRegistry(SchemaRegistryClient schemaRegistry) {
        this.ceSchemaRegistry = schemaRegistry;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        Map<String, Object> conf = new HashMap<>(configs);
        conf.put(ConverterConfig.TYPE_CONFIG, ConverterType.VALUE.getName());
        CloudEventsConverterConfig ceConfig = new CloudEventsConverterConfig(conf);
        ceSerializerType = ceConfig.cloudeventsSerializerType();
        dataSerializerType = ceConfig.cloudeventsDataSerializerTypeConfig();

        switch (ceSerializerType) {
            case JSON:
                final JsonConverterConfig jsonConfig = new JsonConverterConfig(conf);
                conf.put(JsonConverterConfig.SCHEMAS_ENABLE_CONFIG, Boolean.FALSE.toString());
                conf.put(JsonConverterConfig.SCHEMAS_CACHE_SIZE_CONFIG, jsonConfig.schemaCacheSize());
                jsonCEConverter.configure(conf, isKey);
                break;
            case AVRO:
                if (ceSchemaRegistry != null) {
                    avroCEConverter = new AvroConverter(ceSchemaRegistry);
                }
                schemaRegistryUrls = ceConfig.cloudeventsSchemaRegistryUrls();
                if (schemaRegistryUrls == null) {
                    throw new DataException("Need url(s) for schema registry instances for CloudEvents");
                }
                final Map<String, Object> avroConfigs = new HashMap<>(conf);
                avroConfigs.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, String.join(",", schemaRegistryUrls));
                avroCEConverter.configure(avroConfigs, false);
                break;
        }

        switch (dataSerializerType) {
            case JSON:
                enableJsonSchemas = ceConfig.cloudeventsJsonSchemasEnable();
                break;
            case AVRO:
                if (ceSchemaRegistry != null) {
                    avroDataConverter = new AvroConverter(ceSchemaRegistry);
                }
                schemaRegistryUrls = ceConfig.cloudeventsSchemaRegistryUrls();
                if (schemaRegistryUrls == null) {
                    throw new DataException("Need url(s) for schema registry instances for CloudEvents");
                }
                Map<String, Object> avroConfigs = new HashMap<>(configs);
                avroConfigs.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, String.join(",", schemaRegistryUrls));
                avroDataConverter.configure(avroConfigs, false);
                break;
        }
    }

    @Override
    public byte[] fromConnectData(String topic, Schema schema, Object value) {
        if (schema == null || value == null) {
            return null;
        }
        if (schema.type() != STRUCT) {
            throw new DataException("Mismatching schema");
        }

        RecordParser parser = RecordParser.create(schema, value);
        CloudEventsMaker maker = CloudEventsMaker.create(parser, ceSerializerType,
                (schemaRegistryUrls == null) ? null : String.join(",", schemaRegistryUrls));

        Schema dataSchemaType;
        Object serializedData;
        JsonNode serializedJsonData = null;
        byte[] serializedCloudEvents;

        switch (dataSerializerType) {
            case JSON:
                dataSchemaType = maker.ceDataAttributeSchema();
                serializedJsonData = convertToJsonNode(maker.ceDataAttributeSchema(), maker.ceDataAttribute(), enableJsonSchemas);
                serializedData = maker.ceDataAttribute();
                break;
            case AVRO:
                dataSchemaType = Schema.BYTES_SCHEMA;
                serializedData = avroDataConverter.fromConnectData(topic, maker.ceDataAttributeSchema(), maker.ceDataAttribute());
                break;
            default:
                throw new DataException("No such serializer for \"" + dataSerializerType + "\" format");
        }

        SchemaAndValue ceSchemaAndValue = convertToCloudEventsFormat(parser, maker, dataSchemaType, serializedData);
        switch (ceSerializerType) {
            case JSON:
                JsonNode node = convertToJsonNode(ceSchemaAndValue.schema(), ceSchemaAndValue.value(), false);
                ((ObjectNode) node).set(CloudEventsMaker.FieldName.DATA, serializedJsonData);
                serializedCloudEvents = jsonSerializer.serialize(topic, node);
                break;
            case AVRO:
                serializedCloudEvents = avroCEConverter.fromConnectData(topic, ceSchemaAndValue.schema(), ceSchemaAndValue.value());
                break;
            default:
                throw new DataException("No such serializer for \"" + dataSerializerType + "\" format");
        }

        return serializedCloudEvents;
    }

    @Override
    public SchemaAndValue toConnectData(String topic, byte[] value) {
        switch (ceSerializerType) {
            case JSON:
                JsonNode jsonValue;

                try {
                    jsonValue = jsonDeserializer.deserialize(topic, value);
                    byte[] data = jsonValue.get(CloudEventsMaker.FieldName.DATA).binaryValue();
                    SchemaAndValue dataField = reconvertData(topic, data, dataSerializerType, enableJsonSchemas);
                    Schema incompleteSchema = jsonCEConverter.asConnectSchema(jsonValue);
                    SchemaBuilder builder = SchemaBuilder.struct();

                    for (Field ceField : incompleteSchema.fields()) {
                        if (ceField.name().equals(CloudEventsMaker.FieldName.DATA)) {
                            builder.field(ceField.name(), dataField.schema());
                        }
                        else {
                            builder.field(ceField.name(), ceField.schema());
                        }
                    }
                    builder.name(incompleteSchema.name());
                    builder.version(incompleteSchema.version());
                    builder.doc(incompleteSchema.doc());
                    for (Map.Entry<String, String> entry : incompleteSchema.parameters().entrySet()) {
                        builder.parameter(entry.getKey(), entry.getValue());
                    }
                    Schema schema = builder.build();

                    Struct incompleteStruct = (Struct) CONVERT_TO_CONNECT_METHOD.invoke(jsonCEConverter, incompleteSchema, jsonValue);
                    Struct struct = new Struct(schema);

                    for (Field ceField : incompleteSchema.fields()) {
                        if (ceField.name().equals(CloudEventsMaker.FieldName.DATA)) {
                            struct.put(ceField, dataField.value());
                        }
                        struct.put(ceField, incompleteStruct.get(ceField));
                    }

                    return new SchemaAndValue(schema, value);
                }
                catch (SerializationException | IOException | IllegalAccessException | InvocationTargetException e) {
                    throw new DataException("Converting byte[] to Kafka Connect data failed due to serialization error: ", e);
                }
            case AVRO:
                // First reconvert the whole CloudEvents
                // Then reconvert the "data" field
                SchemaAndValue ceSchemaAndValue = avroCEConverter.toConnectData(topic, value);
                Schema incompleteSchema = ceSchemaAndValue.schema();
                Struct ceValue = (Struct) ceSchemaAndValue.value();
                byte[] data = ceValue.getBytes(CloudEventsMaker.FieldName.DATA);
                SchemaAndValue dataSchemaAndValue = avroDataConverter.toConnectData(topic, data);
                SchemaBuilder builder = SchemaBuilder.struct();

                for (Field ceField : incompleteSchema.fields()) {
                    if (ceField.name().equals(CloudEventsMaker.FieldName.DATA)) {
                        builder.field(ceField.name(), dataSchemaAndValue.schema());
                    }
                    else {
                        builder.field(ceField.name(), ceField.schema());
                    }
                }
                builder.name(incompleteSchema.name());
                builder.version(incompleteSchema.version());
                builder.doc(incompleteSchema.doc());
                if (incompleteSchema.parameters() != null) {
                    for (Map.Entry<String, String> entry : incompleteSchema.parameters().entrySet()) {
                        builder.parameter(entry.getKey(), entry.getValue());
                    }
                }
                Schema schema = builder.build();

                Struct struct = new Struct(schema);
                for (Field field : schema.fields()) {
                    if (field.name().equals(CloudEventsMaker.FieldName.DATA)) {
                        struct.put(field, dataSchemaAndValue.value());
                    }
                    else {
                        struct.put(field, ceValue.get(field));
                    }
                }

                return new SchemaAndValue(schema, struct);
        }

        return SchemaAndValue.NULL;
    }

    private SchemaAndValue reconvertData(String topic, byte[] serializedData, SerializerType dataType, Boolean enableSchemas) {
        switch (dataType) {
            case JSON:
                JsonNode jsonValue;

                try {
                    jsonValue = jsonDeserializer.deserialize(topic, serializedData);
                }
                catch (SerializationException e) {
                    throw new DataException("Converting byte[] to Kafka Connect data failed due to serialization error: ", e);
                }

                if (!enableSchemas) {
                    ObjectNode envelope = JsonNodeFactory.instance.objectNode();
                    envelope.set(CloudEventsMaker.FieldName.SCHEMA_FIELD_NAME, null);
                    envelope.set(CloudEventsMaker.FieldName.PAYLOAD_FIELD_NAME, jsonValue);
                    jsonValue = envelope;
                }

                Schema schema = jsonCEConverter.asConnectSchema(jsonValue.get(CloudEventsMaker.FieldName.SCHEMA_FIELD_NAME));

                try {
                    return new SchemaAndValue(
                            schema,
                            CONVERT_TO_CONNECT_METHOD.invoke(jsonCEConverter, schema, jsonValue.get(CloudEventsMaker.FieldName.PAYLOAD_FIELD_NAME)));
                }
                catch (IllegalAccessException | InvocationTargetException e) {
                    throw new DataException(e.getCause());
                }
            case AVRO:
                return avroDataConverter.toConnectData(topic, serializedData);
            default:
                throw new DataException("No such serializer for \"" + dataSerializerType + "\" format");
        }
    }

    private SchemaAndValue convertToCloudEventsFormat(RecordParser parser, CloudEventsMaker maker, Schema dataSchemaType, Object serializedData) {
        SchemaNameAdjuster schemaNameAdjuster = SchemaNameAdjuster.create(LOGGER);
        AttributeNameAdjuster attributeAdjuster = ((AttributeNameAdjuster) CloudEventsConverter::adjustExtensionName).uniqueName();
        String dataSchema = maker.ceDataschema();
        Struct source = parser.source();
        Schema sourceSchema = parser.source().schema();

        // construct schema of CloudEvents envelope
        CESchemaBuilder ceSchemaBuilder = defineSchema()
                .withName(schemaNameAdjuster.adjust(maker.ceEnvelopeSchemaName()))
                .withSchema(CloudEventsMaker.FieldName.DATACONTENTTYPE, Schema.STRING_SCHEMA)
                .withSchema(CloudEventsMaker.FieldName.TIME, Schema.STRING_SCHEMA)
                .withSchema(CloudEventsMaker.FieldName.DATA, dataSchemaType)
                .withSchema(attributeAdjuster.adjust(Envelope.FieldName.OPERATION), Schema.STRING_SCHEMA)
                .withSchema(attributeAdjuster.adjust(Envelope.FieldName.TIMESTAMP), Schema.STRING_SCHEMA);

        if (dataSchema != null) {
            ceSchemaBuilder.withSchema(CloudEventsMaker.FieldName.DATASCHEMA, Schema.STRING_SCHEMA);
        }

        for (Field field : sourceSchema.fields()) {
            Schema schema = field.schema();
            if (Schema.BOOLEAN_SCHEMA.equals(schema)) {
                ceSchemaBuilder.withSchema(attributeAdjuster.adjust(field.name()), Schema.BOOLEAN_SCHEMA);
            }
            else if (Schema.INT8_SCHEMA.equals(schema) || Schema.INT16_SCHEMA.equals(schema) || Schema.INT32_SCHEMA.equals(schema)) {
                ceSchemaBuilder.withSchema(attributeAdjuster.adjust(field.name()), Schema.INT32_SCHEMA);
            }
            else {
                ceSchemaBuilder.withSchema(attributeAdjuster.adjust(field.name()), Schema.STRING_SCHEMA);
            }
        }

        Schema ceSchema = ceSchemaBuilder.build();

        // construct value of CloudEvents Envelope
        CEValueBuilder ceValueBuilder = withValue(ceSchema)
                .withValue(CloudEventsMaker.FieldName.ID, maker.ceId())
                .withValue(CloudEventsMaker.FieldName.SOURCE, maker.ceSource())
                .withValue(CloudEventsMaker.FieldName.SPECVERSION, maker.ceSpecversion())
                .withValue(CloudEventsMaker.FieldName.TYPE, maker.ceType())
                .withValue(CloudEventsMaker.FieldName.DATACONTENTTYPE, maker.ceDatacontenttype())
                .withValue(CloudEventsMaker.FieldName.TIME, maker.ceTime())
                .withValue(CloudEventsMaker.FieldName.DATA, serializedData)
                .withValue(attributeAdjuster.adjust(Envelope.FieldName.OPERATION), parser.op())
                .withValue(attributeAdjuster.adjust(Envelope.FieldName.TIMESTAMP), parser.ts_ms());

        if (dataSchema != null) {
            ceValueBuilder.withValue(CloudEventsMaker.FieldName.DATASCHEMA, dataSchema);
        }

        for (Field field : sourceSchema.fields()) {
            ceValueBuilder.withValue(attributeAdjuster.adjust(field.name()), source.get(field));
        }

        return new SchemaAndValue(ceSchema, ceValueBuilder.build());
    }

    private JsonNode convertToJsonNode(Schema schema, Object value, boolean enableJsonSchemas) {
        try {
            JsonNode withoutSchemaNode = (JsonNode) CONVERT_TO_JSON_METHOD.invoke(jsonCEConverter, schema, value);

            if (!enableJsonSchemas) {
                return withoutSchemaNode;
            }

            ObjectNode schemaNode = jsonCEConverter.asJsonSchema(schema);
            ObjectNode withSchemaNode = JsonNodeFactory.instance.objectNode();
            withSchemaNode.set(CloudEventsMaker.FieldName.SCHEMA_FIELD_NAME, schemaNode);
            withSchemaNode.set(CloudEventsMaker.FieldName.PAYLOAD_FIELD_NAME, withoutSchemaNode);

            return withSchemaNode;
        }
        catch (IllegalAccessException | InvocationTargetException e) {
            throw new DataException(e.getCause());
        }
    }

    public static CESchemaBuilder defineSchema() {
        return new CESchemaBuilder() {
            private final SchemaBuilder builder = SchemaBuilder.struct();
            private final Set<String> missingFields = new HashSet<>();

            @Override
            public CESchemaBuilder withName(String name) {
                builder.name(name);
                return this;
            }

            @Override
            public CESchemaBuilder withSchema(String fieldName, Schema fieldSchema) {
                builder.field(fieldName, fieldSchema);
                return this;
            }

            @Override
            public Schema build() {
                // Required attributes
                builder.field(CloudEventsMaker.FieldName.ID, Schema.STRING_SCHEMA);
                builder.field(CloudEventsMaker.FieldName.SOURCE, Schema.STRING_SCHEMA);
                builder.field(CloudEventsMaker.FieldName.SPECVERSION, Schema.STRING_SCHEMA);
                builder.field(CloudEventsMaker.FieldName.TYPE, Schema.STRING_SCHEMA);

                // Check required extension attribute names
                checkFieldIsDefined(adjustExtensionName(Envelope.FieldName.OPERATION));
                checkFieldIsDefined(adjustExtensionName(Envelope.FieldName.TIMESTAMP));
                // Check the data attribute
                checkFieldIsDefined(CloudEventsMaker.FieldName.DATA);

                if (!missingFields.isEmpty()) {
                    throw new IllegalStateException("The envelope schema is missing field(s) " + String.join(", ", missingFields));
                }

                return builder.build();
            }

            private void checkFieldIsDefined(String fieldName) {
                if (builder.field(fieldName) == null) {
                    missingFields.add(fieldName);
                }
            }
        };
    }

    public static CEValueBuilder withValue(Schema schema) {
        return new CEValueBuilder() {
            private final Schema ceSchema = schema;
            private final Struct ceValue = new Struct(ceSchema);

            @Override
            public CEValueBuilder withValue(String fieldName, Object value) {
                if (ceSchema.field(fieldName) == null) {
                    throw new DataException(fieldName + " is not a valid field name");
                }

                ceValue.put(fieldName, value);
                return this;
            }

            @Override
            public Struct build() {
                return ceValue;
            }
        };
    }

    /**
     * Builder of a CloudEvents envelope schema.
     */
    public interface CESchemaBuilder {

        CESchemaBuilder withName(String name);

        CESchemaBuilder withSchema(String fieldName, Schema fieldSchema);

        Schema build();
    }

    /**
     * Builder of a CloudEvents value.
     */
    public interface CEValueBuilder {

        CEValueBuilder withValue(String fieldName, Object value);

        Struct build();
    }

    /**
     * An adjuster for the name of CloudEvents attributes.
     */
    @FunctionalInterface
    public interface AttributeNameAdjuster {
        /**
         * Convert the original field name to a valid CloudEvents attribute name, simply removing all invalid
         * characters
         *
         * @param original the original attribute name
         * @return the valid CloudEvents attribute name
         */
        String adjust(String original);

        /**
         * Create a new function that keep tracking all produced attribute names, in case of duplicated names.
         *
         * @return the new function; never null
         */
        default AttributeNameAdjuster uniqueName() {
            Set<String> alreadySeen = Collections.newSetFromMap(new ConcurrentHashMap<>());
            return original -> {
                String replacement = this.adjust(original);
                if (!alreadySeen.add(replacement)) {
                    throw new DataException("Duplicated CloudEvents extension attribute name " + replacement);
                }
                return replacement;
            };
        }
    }

    /**
     * Adjust the name of CloudEvents attributes for Debezium events, following CloudEvents
     * <a href="https://github.com/cloudevents/spec/blob/v1.0/spec.md#attribute-naming-conventionattribute"> attribute
     * naming convention</a> as follows:
     *
     * CloudEvents attribute names MUST consist of lower-case letters ('a' to 'z') or digits ('0' to '9') from the ASCII
     * character set. Attribute names SHOULD be descriptive and terse and SHOULD NOT exceed 20 characters in length.
     *
     * @param original the original field name
     * @return the valid extension attribute name
     */
    public static String adjustExtensionName(String original) {
        if (original.length() == 0) {
            return original;
        }
        StringBuilder sb = new StringBuilder();
        char c;
        for (int i = 0; i != original.length(); ++i) {
            c = original.charAt(i);
            if (isValidExtensionNameCharacter(c)) {
                sb.append(c);
            }
        }
        if (sb.length() > 20) {
            return sb.substring(0, 20);
        }
        return sb.toString();
    }

    private static boolean isValidExtensionNameCharacter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}