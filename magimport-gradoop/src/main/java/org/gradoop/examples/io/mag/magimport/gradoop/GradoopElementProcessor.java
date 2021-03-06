/**
 * Copyright 2017 The magimport contributers.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradoop.examples.io.mag.magimport.gradoop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.gradoop.examples.io.mag.magimport.callback.ElementProcessor;
import org.gradoop.examples.io.mag.magimport.data.MagObject;
import org.gradoop.examples.io.mag.magimport.data.TableSchema;
import org.apache.log4j.Logger;
import org.gradoop.common.model.impl.properties.Properties;
import org.gradoop.common.model.impl.properties.PropertyValue;
import org.gradoop.examples.io.mag.magimport.data.FieldType;
import org.gradoop.examples.io.mag.magimport.data.ForeignKey;
import org.gradoop.flink.io.impl.graph.tuples.ImportEdge;
import org.gradoop.flink.io.impl.graph.tuples.ImportVertex;

/**
 * An {@link ElementProcessor} writing the graph to gradoop.
 */
public class GradoopElementProcessor implements ElementProcessor {

    /**
     * Logger of this class.
     */
    private static final Logger LOG
            = Logger.getLogger(GradoopElementProcessor.class);

    /**
     * Mapping from schema name to schema used for performance.
     */
    private Map<String, TableSchema> graphSchema;

    /**
     * List of result vertices.
     */
    private final List<ImportVertex<String>> nodes;

    /**
     * List of result edges.
     */
    private final List<ImportEdge<String>> edges;

    /**
     * Stores paper properties using their id. (Workaround for
     * {@link TableSchema.ObjectType#MULTI_ATTRIBUTE}.
     */
    private final Map<String, Properties> paperProp;

    /**
     * Initialize the processor with a set of {@link TableSchema schemata}.
     *
     * @param schemata Schemata used to resolve foreign keys.
     */
    public GradoopElementProcessor(Collection<TableSchema> schemata) {
        graphSchema = new TreeMap<>();
        schemata.iterator().forEachRemaining(t
                -> graphSchema.put(t.getSchemaName(), t));
        nodes = new ArrayList<>(20000);
        edges = new ArrayList<>(20000);

        paperProp = new HashMap<>(20000);
    }

    /**
     * Get the resulting edges.
     *
     * @return A list of resulting edges.
     */
    public List<ImportEdge<String>> getResultEdges() {
        return edges;
    }

    /**
     * Get the resulting vertices.
     *
     * @return A list of resulting vertices.
     */
    public List<ImportVertex<String>> getResultVertices() {
        return nodes;
    }

    @Override
    public void process(MagObject obj) {
        switch (obj.getSchema().getType()) {
            case NODE: {
                Optional<String> id = getId(obj);
                if (!id.isPresent()) {
                    LOG.warn("No id present on " + obj.toString());
                    return;
                }
                Properties prop = convertAttributes(obj);
                if (obj.getSchema().getSchemaName().equals("Papers")) {
                    paperProp.put(id.get(), prop);
                }
                ImportVertex<String> vertex = new ImportVertex<>(id.get(),
                        obj.getSchema().getSchemaName(), prop);

                nodes.add(vertex);
                LOG.debug("Added Node: " + vertex.toString());

                getForeignKeys(obj).stream()
                        .map(e -> new ImportEdge<String>(id.get() + '|'
                        + e, id.get(), e.key,
                        obj.getSchema().getSchemaName() + "|"
                        + e.scope))
                        .forEach(edges::add);

                break;
            }
            case EDGE: {
                Properties prop = convertAttributes(obj);
                List<ForeignKey> keys = getForeignKeys(obj);

                if (keys.size() < 2) {
                    LOG.warn("Malformed edge, not enough keys " + obj.toString());
                }
                if (keys.size() > 2) {
                    LOG.warn("Malformed edge, too many keys " + obj.toString());
                }

                Iterator<ForeignKey> it = keys.iterator();
                String source = it.next().key;
                String target = it.next().key;

                ImportEdge<String> edge = new ImportEdge<>(source + '|'
                        + target, source, target,
                        obj.getSchema().getSchemaName(),
                        prop);

                edges.add(edge);
                LOG.debug("Added Edge: " + edge.toString());
                break;
            }
            case EDGE_3: {
                Properties propFirst = convertAttributesEdge3(obj, true);
                List<String> keysFirst
                        = getForeignKeysEdge3(obj, true);

                Properties propSecond = convertAttributesEdge3(obj, false);
                List<String> keysSecond
                        = getForeignKeysEdge3(obj, false);

                if (keysFirst.size() != 2 || keysSecond.size() != 2) {
                    LOG.warn("Malformed multiedge " + obj.toString());
                }

                Iterator<String> itFirst = keysFirst.iterator();
                String sourceFirst = itFirst.next();
                String targetFirst = itFirst.next();

                Iterator<String> itSecond = keysSecond.iterator();
                String sourceSecond = itSecond.next();
                String targetSecond = itSecond.next();

                ImportEdge<String> edgeFirst = new ImportEdge<>(sourceFirst
                        + '|' + targetFirst, sourceFirst, targetFirst,
                        obj.getSchema().getSchemaName() + "_1", propFirst);
                ImportEdge<String> edgeSecond = new ImportEdge<>(sourceSecond
                        + '|' + targetSecond, sourceSecond, targetSecond,
                        obj.getSchema().getSchemaName() + "_2", propSecond);

                edges.add(edgeFirst);
                LOG.debug("Added Edge3 first: " + edgeFirst.toString());
                edges.add(edgeSecond);
                LOG.debug("Added Edge3 second: " + edgeSecond.toString());
                break;
            }
            case MULTI_ATTRIBUTE: {
                List<ForeignKey> keys = getForeignKeys(obj);
                if (keys.size() != 1) {
                    LOG.warn("Malformed multi-attribute " + obj.toString());
                }

                Iterator<ForeignKey> it = keys.iterator();
                String oid = it.next().key;
                Properties prop = paperProp.get(oid);
                if (!prop.containsKey(obj.getSchema().getSchemaName())) {
                    prop.set(obj.getSchema().getSchemaName(),
                            new ArrayList<String>());
                }
                List<PropertyValue> urlList
                        = prop.get(obj.getSchema().getSchemaName()).getList();

                Properties urlProp = convertAttributes(obj);
                urlList.add(urlProp.get("URL"));

                prop.set(obj.getSchema().getSchemaName(), urlList);

                LOG.debug("Added Multi Attribute: " + urlList.toString());

                break;
            }

        }
    }

    /**
     * Get foreign keys of a {@link MagObject}.
     *
     * @param obj Object to get keys from.
     * @return A map storing table and id of the foreign object.
     */
    private List<ForeignKey> getForeignKeys(MagObject obj) {
        List<FieldType> types = obj.getSchema().getFieldTypes();
        List<String> fieldNames = obj.getSchema().getFieldNames();
        List<ForeignKey> keys = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if (!types.get(i).equals(FieldType.KEY)) {
                continue;
            }
            String[] name = fieldNames.get(i)
                    .split(String.valueOf(TableSchema.SCOPE_SEPARATOR));
            if (name.length != 2) {
                LOG.warn("Malformed key column name: " + fieldNames.get(i));
                continue;
            }
            TableSchema targetSchema = graphSchema.get(name[0]);
            if (targetSchema == null) {
                LOG.warn("Foreign key to unknown table: " + name[0]);
                continue;
            }
            keys.add(new ForeignKey(name[0], obj.getFieldData(i)));
        }
        return keys;
    }

    /**
     * Get foreign keys of a {@link MagObject}.
     *
     * @param obj Object to get keys from.
     * @param firstRun First or second edge in
     * {@link TableSchema.ObjectType#EDGE_3}.
     * @return A map storing table and id of the foreign object.
     */
    private List<String> getForeignKeysEdge3(MagObject obj,
            boolean firstRun) {
        List<FieldType> types = obj.getSchema().getFieldTypes();
        List<String> fieldNames = obj.getSchema().getFieldNames();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if (!types.get(i).equals(FieldType.KEY)
                    && ((firstRun
                    && !types.get(i).equals(FieldType.KEY_1))
                    || (!firstRun
                    && !types.get(i).equals(FieldType.KEY_2)))) {
                continue;
            }
            String[] name = fieldNames.get(i)
                    .split(String.valueOf(TableSchema.SCOPE_SEPARATOR));
            if (name.length != 2) {
                LOG.warn("Malformed key column name: " + fieldNames.get(i));
                continue;
            }
            TableSchema targetSchema = graphSchema.get(name[0]);
            if (targetSchema == null) {
                LOG.warn("Foreign key to unknown table: " + name[0]);
                continue;
            }
            keys.add(obj.getFieldData(i));
        }
        return keys;
    }

    /**
     * Convert {@link MagObject}s attributes to {@link Properties}.
     *
     * @param obj Object to get attributes from.
     * @return {@link Properties} used in Gradoop.
     */
    private static Properties convertAttributes(MagObject obj) {
        Properties prop = new Properties();
        List<String> names = obj.getSchema().getFieldNames();
        List<FieldType> types = obj.getSchema().getFieldTypes();
        IntStream.range(0, types.size()).filter(i
                -> types.get(i).equals(FieldType.ATTRIBUTE))
                .filter(i -> !obj.getFieldData(i).equals(""))
                .forEach(i -> prop.set(names.get(i), obj.getFieldData(i)));
        return prop;
    }

    /**
     * Convert {@link MagObject}s attributes to {@link Properties}.
     *
     * @param obj Object to get attributes from.
     * @param firstRun First or second edge in
     * {@link TableSchema.ObjectType#EDGE_3}.
     * @return {@link Properties} used in Gradoop.
     */
    private static Properties convertAttributesEdge3(MagObject obj,
            boolean firstRun) {
        Properties prop = new Properties();
        List<String> names = obj.getSchema().getFieldNames();
        List<FieldType> types = obj.getSchema().getFieldTypes();
        IntStream.range(0, types.size()).filter(i
                -> types.get(i).equals(FieldType.ATTRIBUTE)
                || ((firstRun
                && types.get(i).equals(FieldType.ATTRIBUTE_1))
                || (!firstRun
                && types.get(i).equals(FieldType.KEY_1))))
                .filter(i -> !obj.getFieldData(i).equals(""))
                .forEach(i -> prop.set(names.get(i), obj.getFieldData(i)));
        return prop;
    }

    /**
     * Get the ID of an {@link MagObject} if it has an ID.
     *
     * @param obj Object to get ID from.
     * @return ID (as {@link Optional}).
     */
    private static Optional<String> getId(MagObject obj) {
        List<FieldType> types = obj.getSchema().getFieldTypes();
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).equals(FieldType.ID)) {
                return Optional.of(obj.getFieldData(i));
            }
        }
        return Optional.empty();
    }
}
