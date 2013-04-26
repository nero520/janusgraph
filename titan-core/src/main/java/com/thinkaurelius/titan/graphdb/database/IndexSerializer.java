package com.thinkaurelius.titan.graphdb.database;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.attribute.Cmp;
import com.thinkaurelius.titan.diskstorage.BackendTransaction;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.indexing.IndexInformation;
import com.thinkaurelius.titan.diskstorage.indexing.IndexQuery;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.Entry;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeySliceQuery;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.SimpleEntry;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.SliceQuery;
import com.thinkaurelius.titan.graphdb.database.idhandling.VariableLong;
import com.thinkaurelius.titan.graphdb.database.serialize.DataOutput;
import com.thinkaurelius.titan.graphdb.database.serialize.Serializer;
import com.thinkaurelius.titan.graphdb.internal.InternalRelation;
import com.thinkaurelius.titan.graphdb.internal.InternalType;
import com.thinkaurelius.titan.graphdb.query.StandardElementQuery;
import com.thinkaurelius.titan.graphdb.query.keycondition.*;
import com.thinkaurelius.titan.graphdb.relations.RelationIdentifier;
import com.thinkaurelius.titan.util.encoding.LongEncoding;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyColumnValueStore.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * (c) Matthias Broecheler (me@matthiasb.com)
 */

public class IndexSerializer {

    private static final Logger log = LoggerFactory.getLogger(IndexSerializer.class);

    private static final int DEFAULT_VALUE_CAPACITY = 40;

    private final Serializer serializer;
    private final Map<String,? extends IndexInformation> indexes;

    public IndexSerializer(Serializer serializer, Map<String,? extends IndexInformation> indexes) {
        this.serializer = serializer;
        this.indexes = indexes;
    }

    /* ################################################
               Index Updates
    ################################################### */

    public void newPropertyKey(TitanKey key, BackendTransaction tx) throws StorageException {
        for (String index : key.getIndexes(Vertex.class)) {
            if (!index.equals(Titan.Token.STANDARD_INDEX))
                tx.getIndexTransactionHandle(index).register(VERTEXINDEX_NAME,key2String(key),key.getDataType());
        }
        for (String index : key.getIndexes(Edge.class)) {
            if (!index.equals(Titan.Token.STANDARD_INDEX))
                tx.getIndexTransactionHandle(index).register(EDGEINDEX_NAME,key2String(key),key.getDataType());
        }
    }

    public void addProperty(TitanProperty prop, BackendTransaction tx) throws StorageException {
        TitanKey key = prop.getPropertyKey();
        for (String index : key.getIndexes(Vertex.class)) {
            if (index.equals(Titan.Token.STANDARD_INDEX)) {
                if (key.isUnique(Direction.IN)) {
                    tx.mutateVertexIndex(getIndexKey(prop.getValue()),
                            Lists.newArrayList(SimpleEntry.of(getUniqueIndexColumn(key), getIndexValue(prop))), NO_DELETIONS);
                } else {
                    tx.mutateVertexIndex(getIndexKey(prop.getValue()),
                            Lists.newArrayList(SimpleEntry.of(getIndexColumn(key, prop.getID()), getIndexValue(prop))), NO_DELETIONS);
                }
            } else {
                addKeyValue(prop.getVertex(),key,prop.getValue(),index,tx);
            }
        }
    }

    public void removeProperty(TitanProperty prop, BackendTransaction tx) throws StorageException {
        TitanKey key = prop.getPropertyKey();
        for (String index : key.getIndexes(Vertex.class)) {
            if (index.equals(Titan.Token.STANDARD_INDEX)) {
                if (key.isUnique(Direction.IN)) {
                    tx.mutateVertexIndex(getIndexKey(prop.getValue()), NO_ADDITIONS,
                            Lists.newArrayList(getUniqueIndexColumn(key)));
                } else {
                    tx.mutateVertexIndex(getIndexKey(prop.getValue()), NO_ADDITIONS,
                            Lists.newArrayList(getIndexColumn(key, prop.getID())));
                }
            } else {
                removeKeyValue(prop.getVertex(),key,index,tx);
            }
        }
    }

    public void lockKeyedProperty(TitanProperty prop, BackendTransaction tx) throws StorageException {
        TitanKey key = prop.getPropertyKey();
        if (key.isUnique(Direction.IN) && ((InternalType)key).uniqueLock(Direction.IN)) {
            Preconditions.checkArgument(key.hasIndex(Titan.Token.STANDARD_INDEX,Vertex.class),"Standard Index needs to be created for property to be declared unique [%s]",key.getName());
            if (prop.isNew()) {
                tx.acquireVertexIndexLock(getIndexKey(prop.getValue()), getUniqueIndexColumn(key), null);
            } else {
                Preconditions.checkArgument(prop.isRemoved());
                tx.acquireVertexIndexLock(getIndexKey(prop.getValue()), getUniqueIndexColumn(key), getIndexValue(prop));
            }
        }
    }

    public void addEdge(InternalRelation relation, BackendTransaction tx) throws StorageException  {
        Preconditions.checkArgument(relation instanceof TitanEdge,"Only edges can be indexed for now");
        for (TitanType type : relation.getPropertyKeysDirect()) {
            if (type instanceof TitanKey) {
                TitanKey key = (TitanKey)type;
                for (String index : key.getIndexes(Edge.class)) {
                    Object value = relation.getPropertyDirect(key);
                    if (index.equals(Titan.Token.STANDARD_INDEX)) {
                        tx.mutateEdgeIndex(getIndexKey(value),
                                Lists.newArrayList(SimpleEntry.of(getIndexColumn(key, relation.getID()),
                                        relationID2ByteBuffer((RelationIdentifier) relation.getId()))), NO_DELETIONS);
                    } else {
                        addKeyValue(relation,key,value,index,tx);
                    }
                }
            }
        }
    }

    public void removeEdge(InternalRelation relation, BackendTransaction tx) throws StorageException {
        Preconditions.checkArgument(relation instanceof TitanEdge,"Only edges can be indexed for now");
        for (TitanType type : relation.getPropertyKeysDirect()) {
            if (type instanceof TitanKey) {
                TitanKey key = (TitanKey)type;
                for (String index : key.getIndexes(Edge.class)) {
                    Object value = relation.getPropertyDirect(key);
                    if (index.equals(Titan.Token.STANDARD_INDEX)) {
                        tx.mutateEdgeIndex(getIndexKey(value), NO_ADDITIONS,
                                Lists.newArrayList(getIndexColumn(key, relation.getID())));
                    } else {
                        removeKeyValue(relation, key, index, tx);
                    }
                }
            }
        }
    }

    private static final ByteBuffer relationID2ByteBuffer(RelationIdentifier rid) {
        long[] longs = rid.getLongRepresentation();
        Preconditions.checkArgument(longs.length==3);
        ByteBuffer buffer = ByteBuffer.allocate(24);
        for (int i=0;i<3;i++) VariableLong.writePositive(buffer,longs[i]);
        buffer.flip();
        return buffer;
    }

    private static final RelationIdentifier bytebuffer2RelationId(ByteBuffer b) {
        long[] relationId = new long[3];
        for (int i=0;i<3;i++) relationId[i]=VariableLong.readPositive(b);
        return RelationIdentifier.get(relationId);
    }

    private void addKeyValue(TitanElement element, TitanKey key, Object value, String index, BackendTransaction tx) throws StorageException {
        Preconditions.checkArgument(key.isUnique(Direction.OUT),"Only out-unique properties are supported by index [%s]",index);
        tx.getIndexTransactionHandle(index).add(getStoreName(element),element2String(element),key2String(key),value,element.isNew());
    }

    private void removeKeyValue(TitanElement element, TitanKey key, String index, BackendTransaction tx) {
        Preconditions.checkArgument(key.isUnique(Direction.OUT), "Only out-unique properties are supported by index [%s]", index);
        tx.getIndexTransactionHandle(index).delete(getStoreName(element),element2String(element),key2String(key),element.isRemoved());
    }

    /* ################################################
                Querying
    ################################################### */

    public List<Object> query(StandardElementQuery query, BackendTransaction tx) {
        Preconditions.checkArgument(query.hasIndex());
        String index = query.getIndex();
        Preconditions.checkArgument(indexes.containsKey(index),"Index unknown or unconfigured: %s",index);
        if (index.equals(Titan.Token.STANDARD_INDEX)) {
            //Only one equals clause
            KeyAtom<TitanKey> cond = null;
            if (query.getCondition() instanceof KeyAtom) cond = (KeyAtom<TitanKey>)query.getCondition();
            else if (query.getCondition() instanceof KeyAnd || query.getCondition() instanceof KeyOr) {
                cond = (KeyAtom<TitanKey>)Iterables.getOnlyElement(query.getCondition().getChildren());
            }
            Preconditions.checkArgument(cond.getRelation()==Cmp.EQUAL,"Only equality relations are supported by standard index [%s]",cond);
            TitanKey key = cond.getKey();
            Object value = cond.getCondition();
            Preconditions.checkArgument(key.hasIndex(index,query.getType().getElementType()),
                    "Cannot retrieve for given property key - it does not have an index [%s]",key.getName());

            ByteBuffer column = getUniqueIndexColumn(key);
            KeySliceQuery sq = new KeySliceQuery(getIndexKey(value),column, SliceQuery.pointRange(column),query.getLimit(),((InternalType)key).isStatic(Direction.IN));
            List<Entry> r;
            if (query.getType()== StandardElementQuery.Type.VERTEX) {
                r = tx.vertexIndexQuery(sq);
            } else {
                r = tx.edgeIndexQuery(sq);
            }
            List<Object> results = new ArrayList<Object>(r.size());
            for (Entry entry : r) {
                ByteBuffer entryValue = entry.getValue();
                if (query.getType()== StandardElementQuery.Type.VERTEX) {
                    results.add(Long.valueOf(VariableLong.readPositive(entryValue)));
                } else {
                    results.add(bytebuffer2RelationId(entryValue));
                }
            }
            Preconditions.checkArgument(!(query.getType()== StandardElementQuery.Type.VERTEX && key.isUnique(Direction.IN)) || results.size()<=1);
            return results;
        } else {
            verifyQuery(query.getCondition(),index,query.getType().getElementType());
            KeyCondition<String> condition = convert(query.getCondition());
            IndexQuery iquery = new IndexQuery(getStoreName(query),condition,query.getLimit());
            List<String> r = tx.indexQuery(index, iquery);
            List<Object> result = new ArrayList<Object>(r.size());
            for (String id : r) result.add(string2ElementId(id));
            return result;
        }
    }

    private final void verifyQuery(KeyCondition<TitanKey> condition, String indexName, Class<? extends Element> elementType) {
        if (!condition.hasChildren()) {
            KeyAtom<TitanKey> atom = (KeyAtom<TitanKey>)condition;
            Preconditions.checkArgument(atom.getKey().hasIndex(indexName, elementType));
            Preconditions.checkArgument(indexes.get(indexName).supports(atom.getKey().getDataType(), atom.getRelation()));
        } else {
            for (KeyCondition<TitanKey> c : condition.getChildren()) verifyQuery(c,indexName,elementType);
        }
    }

    private static final KeyCondition<String> convert(KeyCondition<TitanKey> condition) {
        if (condition instanceof KeyAtom) {
            KeyAtom<TitanKey> atom = (KeyAtom<TitanKey>) condition;
            Relation relation = atom.getRelation();
            TitanKey key = atom.getKey();
            return KeyAtom.of(key2String(key),relation,atom.getCondition());
        } else if (condition instanceof KeyNot) {
            return KeyNot.of(convert(((KeyNot<TitanKey>)condition).getChild()));
        } else if (condition instanceof KeyAnd || condition instanceof KeyOr) {
            List<KeyCondition<String>> cond = Lists.newArrayList();
            for (KeyCondition<TitanKey> c : condition.getChildren()) {
                cond.add(convert(c));
            }
            if (condition instanceof KeyAnd)
                return KeyAnd.of(cond.toArray(new KeyCondition[cond.size()]));
            else
                return KeyOr.of(cond.toArray(new KeyCondition[cond.size()]));
        } else throw new IllegalArgumentException("Invalid condition: " + condition);
    }

    /* ################################################
                Utility Functions
    ################################################### */

    private static final String element2String(TitanElement element) {
        if (element instanceof TitanVertex) return longID2Name(element.getID());
        else {
            RelationIdentifier rid = (RelationIdentifier) element.getId();
            return rid.toString();
        }
    }

    private static final Object string2ElementId(String str) {
        if (str.contains(RelationIdentifier.TOSTRING_DELIMITER)) return RelationIdentifier.parse(str);
        else return name2LongID(str);
    }

    private static final String key2String(TitanKey key) {
        return longID2Name(key.getID());
    }

    private static final String longID2Name(long id) {
        Preconditions.checkArgument(id>0);
        return LongEncoding.encode(id);
    }

    private static final long name2LongID(String name) {
        return LongEncoding.decode(name);
    }

    public static final String EDGEINDEX_NAME = "edge";
    public static final String VERTEXINDEX_NAME = "vertex";

    private static final String getStoreName(StandardElementQuery query) {
        switch (query.getType()) {
            case VERTEX: return VERTEXINDEX_NAME;
            case EDGE: return EDGEINDEX_NAME;
            default: throw new IllegalArgumentException("Invalid type: " + query.getType());
        }
    }

    private static final String getStoreName(TitanElement element) {
        if (element instanceof TitanVertex) return VERTEXINDEX_NAME;
        else if (element instanceof TitanEdge) return EDGEINDEX_NAME;
        else throw new IllegalArgumentException("Invalid class: " + element.getClass());
    }

    private final ByteBuffer getIndexKey(Object att) {
        DataOutput out = serializer.getDataOutput(DEFAULT_VALUE_CAPACITY, true);
        out.writeObjectNotNull(att);
        return out.getByteBuffer();
    }

    private static final ByteBuffer getIndexValue(TitanProperty prop) {
        return VariableLong.positiveByteBuffer(prop.getVertex().getID());
    }

    private static final ByteBuffer getUniqueIndexColumn(TitanKey type) {
        return VariableLong.positiveByteBuffer(type.getID());
    }

    private static final ByteBuffer getIndexColumn(TitanKey type, long propertyID) {
        return VariableLong.positiveByteBuffer(new long[]{type.getID(), propertyID});
    }

}
