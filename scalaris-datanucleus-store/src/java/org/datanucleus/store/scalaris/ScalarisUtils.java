package org.datanucleus.store.scalaris;

import java.util.ArrayList;
import java.util.List;

import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.ElementMetaData;
import org.datanucleus.metadata.EmbeddedMetaData;
import org.datanucleus.metadata.ExtensionMetaData;
import org.datanucleus.metadata.UniqueMetaData;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.store.StoreManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.transaction.NucleusTransactionException;

import com.ericsson.otp.erlang.OtpErlangLong;
import com.orange.org.json.JSONArray;
import com.orange.org.json.JSONException;
import com.orange.org.json.JSONObject;

import de.zib.scalaris.AbortException;
import de.zib.scalaris.ConnectionException;
import de.zib.scalaris.ErlangValue;
import de.zib.scalaris.NotANumberException;
import de.zib.scalaris.NotFoundException;
import de.zib.scalaris.Transaction;
import de.zib.scalaris.UnknownException;

/**
 * This class contains convenience methods to provide functionalities that are not natively supported by 
 * Scalaris. For example generating identities, managing all primary keys of a class to "iterate" over all stored 
 * instances, or ensuring uniqueness. 
 */
@SuppressWarnings("rawtypes")
public class ScalarisUtils {
    
    /**
     * Key prefix used to signal a key where a collection of all key IDs of the same 
     * type is stored. This is necessary for queries which need access to all stored
     * instances of the same type.
     */
    private static final String ALL_ID_PREFIX = "ALL_IDS";
    
    /**
     * Key prefix used to signal a key which is used for identity generation. Its value
     * is an integer which is incremented every time an ID is generated.
     */
    private static final String ID_GEN_KEY = "ID_GEN";
    
    /**
     * Key prefix used to store all values of members which are marked as "@Unique".
     */
    private static final String UNIQUE_MEMBER_PREFIX = "UNIQUE";
    
    /**
     * Value which will be used to signal a deleted key.
     */
    public static final String DELETED_RECORD_VALUE = new JSONObject().toString();
    
    /**
     * Generate a new ID which can be used to store a value at an unique key.
     * Every time this function is called the value stored at key ID_GEN_KEY is
     * incremented by one. The value stored there is the value which is returned by
     * this function. All object classes share the same ID generator key.
     * 
     * @param op
     *            ObjectProvider of the object this ID is generated for.
     * @return A new ID.
     */
    private static long generateNextIdentity(ObjectProvider op) {
        StoreManager storeMgr = op.getExecutionContext().getStoreManager();
        
        ExecutionContext ec = op.getExecutionContext();
        String keyName = getIDGeneratorKeyName(op.getClassMetaData().getFullClassName());
        ManagedConnection mConn = storeMgr.getConnection(ec);
        de.zib.scalaris.Connection conn = (de.zib.scalaris.Connection) mConn
                .getConnection();

        long newID = 0l;
        Transaction t = new Transaction(conn);
        try {
            try {
                ErlangValue storedVal = t.read(keyName);
                newID = storedVal.longValue() + 1l;
                t.addOnNr(keyName, new OtpErlangLong(1l));
            } catch (NotFoundException e) {
                // No ID was generated yet
                newID = 1l;
                t.write(keyName, newID);
            }

            t.commit();
        } catch (ConnectionException e) {
            throw new NucleusTransactionException(
                    "Could not generate a new ID because of transaction failure",
                    e);
        } catch (AbortException e) {
            throw new NucleusTransactionException(
                    "Could not generate a new ID becasue of transaction failure",
                    e);
        } catch (ClassCastException e) {
            // This happens if the key does not exist
            // which means no ID was generated yet.
            throw new NucleusTransactionException(
                    "The value of the ID generator key was altered to an invalid value",
                    e);
        } catch (NotANumberException e) {
            // this should never ever happen since the ClassCastException
            // is thrown before we can try to increment the number
            throw new NucleusTransactionException(
                    "The value of the ID generator key was altered to an invalid value",
                    e);
        } finally {
            mConn.release();
        }

        return newID;
    }

    /**
     * Returns the object identity as string which can be used of the key-value
     * store as key to uniquely identify the object. ATTENTION: If the data
     * store is (partially) responsible to generate an ID (e.g. because of
     * IdGeneratorStrategy.IDENTITY). This method may change primary key
     * attribute values. This method should be used only to insert a new object
     * in the data store, otherwise consider using getPersitableIdentity.
     * 
     * @param op
     *            data source.
     * @return Identity of object provided by op or null if at least one primary
     *         key field is not loaded.
     */
    static String generatePersistableIdentity(ObjectProvider op) {
        StoreManager storeMgr = op.getExecutionContext().getStoreManager();
        AbstractClassMetaData cmd = op.getClassMetaData();

        if (cmd.pkIsDatastoreAttributed(storeMgr)) {
            // The primary key must be (partially) calculated by the data store.
            // There is no distinction between APPLICATION and DATASTORE
            // IdentityType (yet)

            int[] pkFieldNumbers = cmd.getPKMemberPositions();
            long idKey = 0;
            for (int i = 0; i < pkFieldNumbers.length; i++) {
                AbstractMemberMetaData mmd = cmd
                        .getMetaDataForManagedMemberAtAbsolutePosition(pkFieldNumbers[i]);

                if (storeMgr.isStrategyDatastoreAttributed(cmd,
                        pkFieldNumbers[i])) {
                    Class mType = mmd.getType();
                    if (!(mType.equals(Long.class) || mType.equals(long.class)
                            || mType.equals(Integer.class) || mType
                                .equals(int.class))) {
                        // Field type must be long/Long or int/Integer since
                        // this is the only IDENTITY value that is currently
                        // supported
                        throw new NucleusUserException(
                                "Any field using IDENTITY value generation with Scalaris should be of type long/Long or int/Integer");
                    }
                    idKey = generateNextIdentity(op);
                    if (mType.equals(Integer.class) || mType.equals(int.class)) {
                        if (idKey > Integer.MAX_VALUE) {
                            throw new NucleusException("We ran out of integer IDs!");
                        }
                        op.replaceField(mmd.getAbsoluteFieldNumber(), (int) idKey);
                    } else {
                        op.replaceField(mmd.getAbsoluteFieldNumber(), idKey);
                    }
                    System.out.println("GENERATED KEY: " + idKey);
                }
            }

            String identity = getPersistableIdentity(op);
            // TODO is this right?
            // DataNucleus expects as internal object id an integer value if there is only one
            // primary key member which is an integer. Otherwise it can be an arbitrary
            // object.
            if (pkFieldNumbers.length == 1) {
                op.setPostStoreNewObjectId(idKey);
            } else {
                op.setPostStoreNewObjectId(identity);
            }
            return identity;
        } else {
            // nothing must be done
            return IdentityUtils.getPersistableIdentityForId(op
                    .getExternalObjectId());
        }
    }

    /**
     * Returns the object identity as string which can be used of the key-value
     * store as key to uniquely identify the object.
     * 
     * @param op
     * @return Identity of object provided by op or null if at least one primary
     *         key field is not loaded.
     */
    static String getPersistableIdentity(ObjectProvider op) {
        StoreManager storeMgr = op.getExecutionContext().getStoreManager();
        AbstractClassMetaData cmd = op.getClassMetaData();
        String keySeparator = ":";

        if (cmd.pkIsDatastoreAttributed(storeMgr)) {
            // The primary key must be (partially) calculated by the data store.
            // There is no distinction between APPLICATION and DATASTORE
            // IdentityType (yet)
            // ID structure is: <class-name>:<pk1>:<pk2>

            StringBuilder keyBuilder = new StringBuilder(cmd.getFullClassName());

            int[] pkFieldNumbers = cmd.getPKMemberPositions();
            for (int i = 0; i < pkFieldNumbers.length; i++) {
                AbstractMemberMetaData mmd = cmd
                        .getMetaDataForManagedMemberAtAbsolutePosition(pkFieldNumbers[i]);

                keyBuilder.append(keySeparator);
                keyBuilder
                        .append(op.provideField(mmd.getAbsoluteFieldNumber()));
            }

            return keyBuilder.toString();
        } else {
            // The data store has nothing to do with generating a key value
            return IdentityUtils.getPersistableIdentityForId(op
                    .getExternalObjectId());
        }
    }

    static void performScalarisManagementForInsert(ObjectProvider op, JSONObject json, Transaction t) 
            throws ConnectionException, ClassCastException, UnknownException, JSONException {
        
        insertObjectToAllKey(op, t);
        updateUniqueMemberKey(op, json, t);
    }
    
    static void performScalarisManagementForUpdate(ObjectProvider op, JSONObject json, Transaction t) 
            throws ConnectionException, ClassCastException, UnknownException, JSONException {
        updateUniqueMemberKey(op, json, t);
    }
    
    static void performScalarisManagementForDelete(ObjectProvider op, Transaction t) 
            throws ConnectionException, ClassCastException, UnknownException, JSONException {
        
        removeObjectFromAllKey(op, t);
        removeObjectFromUniqueMemberKey(op, t);
    }
    
    /**
     * Convenience method which returns the key containing all stored identities
     * of the given class.
     * 
     * @param clazz
     *            The class for which the key is generated for.
     * @return Scalaris key as string.
     */
    private static String getManagementKeyName(Class<?> clazz) {
        return getManagementKeyName(clazz.getCanonicalName());
    }

    private static String getManagementKeyName(String className) {
        return String.format("%s_%s", className, ALL_ID_PREFIX);
    }

    private static String getIDGeneratorKeyName(String className) {
        return String.format("%s_%s", className, ID_GEN_KEY);
    }
    
    private static String getUniqueMemberValueToIdKeyName(String className, String memberName, String memberValue) {
        return String.format("%s_%s_%s_%s", className, memberName, memberValue, UNIQUE_MEMBER_PREFIX);
    }
    
    private static String geIdToUniqueMemberValueKeyName(String objectId, String memberName) {
        return String.format("%s_%s_%s", objectId, memberName, UNIQUE_MEMBER_PREFIX);
    }
    
    /**
     * To support queries it is necessary to have the possibility to iterate
     * over all stored objects of a specific type. Since Scalaris stores only
     * key-value pairs without structured tables, this is not "natively"
     * supported. Therefore an extra key is added to the store containing all
     * keys of available objects of a type. This key has the structure
     * <full-class-name><ALL_ID_PREFIX>. The value is an JSON-array containing
     * all keys of <full-class-name> instances.
     * 
     * This methods adds another entry to such a key based on the passed
     * ObjectProvider. If no such key-value pair exists, it is created.
     * 
     * @param op
     *            The data source
     * @throws JSONException 
     * @throws UnknownException 
     * @throws ClassCastException 
     * @throws ConnectionException 
     */
    private static void insertObjectToAllKey(ObjectProvider op, Transaction t) 
            throws ConnectionException, ClassCastException, UnknownException, JSONException {
        
        AbstractClassMetaData cmd = op.getClassMetaData();
        String key = getManagementKeyName(cmd.getFullClassName());
        String objectStringIdentity = getPersistableIdentity(op);

        // retrieve the existing value (null if it does not exist).
        JSONArray json = null;
        try {
            json = new JSONArray(t.read(key).stringValue());
        } catch (NotFoundException e) {
            // the key does not exist.
        }

        // add the new identity if it does not already exists
        if (json == null) {
            json = new JSONArray();
        }
        for (int i = 0; i < json.length(); i++) {
            String s = json.getString(i);
            if (s != null && s.equals(objectStringIdentity)) {
                // This object identity is already stored here
                // It is not necessary to write since nothing changed.
                return;
            }
        }
        json.put(objectStringIdentity);

        // commit changes
        t.write(key, json.toString());
    }

    /**
     * To support queries it is necessary to have the possibility to iterate
     * over all stored objects of a specific type. Since Scalaris stores only
     * key-value pairs without structured tables, this is not "natively"
     * supported. Therefore an extra key is added to the store containing all
     * keys of available objects of a type. This key has the structure
     * <full-class-name><ALL_KEY_PREFIX>. The value is an JSON-array containing
     * all keys of <full-class-name> instances.
     * 
     * This methods removes an entry of such a key based on the passed
     * ObjectProvider. If no such key-value pair exists, nothing happens.
     * 
     * @param op
     *            The data source
     * @throws JSONException 
     * @throws UnknownException 
     * @throws ClassCastException 
     * @throws ConnectionException 
     */
    private static void removeObjectFromAllKey(ObjectProvider op, Transaction t) 
            throws ConnectionException, ClassCastException, UnknownException, JSONException {
        
        AbstractClassMetaData cmd = op.getClassMetaData();
        String key = getManagementKeyName(cmd.getFullClassName());
        String objectStringIdentity = getPersistableIdentity(op);

        // retrieve the existing value (null if it does not exist).
        JSONArray json = null;
        try {
            json = new JSONArray(t.read(key).stringValue());
        } catch (NotFoundException e) {
            // the key does not exist, therefore there is nothing to do
            // here.
            return;
        }

        // remove all occurrences of the key
        ArrayList<String> list = new ArrayList<String>(json.length());
        for (int i = 0; i < json.length(); i++) {
            String s = json.getString(i);
            if (s != null && !s.equals(objectStringIdentity)) {
                list.add(s);
            }
        }
        json = new JSONArray(list);

       // commit changes
       t.write(key, json.toString());
    }
    
    private static void updateUniqueMemberKey(ObjectProvider op, JSONObject json, Transaction t) 
            throws ConnectionException, ClassCastException, UnknownException, JSONException {
        AbstractClassMetaData cmd = op.getClassMetaData();
        String objectStringIdentity = getPersistableIdentity(op);
        String className = cmd.getFullClassName();
        
        for (int field : cmd.getAllMemberPositions()) {
            AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(field);
            UniqueMetaData umd = mmd.getUniqueMetaData();
            if (umd != null) {
                // this member has @Unique annotation -> lookup all stored values for this member
                String fieldName = mmd.getName();
                String fieldValue = null;
                try {
                    fieldValue = json.getString(fieldName);
                } catch (JSONException e) {
                    // unique members can be null which means they are not found in the JSON
                    fieldValue = "";
                }
                    
                String idToValueKey = geIdToUniqueMemberValueKeyName(objectStringIdentity, fieldName);
                String valueToIdKey = getUniqueMemberValueToIdKeyName(className, fieldName, fieldValue);

                String idStoringThisValue = null;
                String oldValueByThisId = null;
                try {
                    idStoringThisValue = t.read(valueToIdKey).stringValue();
                } catch (NotFoundException e) {} // handled below
                try {
                    oldValueByThisId = t.read(idToValueKey).stringValue();
                } catch(NotFoundException e) {} // handled below 

                if (!fieldValue.isEmpty() && idStoringThisValue != null && !idStoringThisValue.isEmpty()) {
                    // the unique value we try to store already exist
                    if (idStoringThisValue.equals(objectStringIdentity)) {
                        // .. but the current object is the one storing this value
                        // This can happen if the current object was updated but this field
                        // was unchanged. We don't need to do anything here.
                    } else {
                        // another object has stored this value -> violation of uniqueness
                        throw new NucleusDataStoreException("The value '" + fieldValue + "' of unique member '" + 
                                fieldName + "' of class '" + className + "' already exists");
                    }
                } else {
                    // the unique value does not exist

                    if (oldValueByThisId != null && !idStoringThisValue.isEmpty()) {
                        // the current object has a value of this member stored -> delete the old entry
                        String oldValueToIdKey = getUniqueMemberValueToIdKeyName(className, fieldName, oldValueByThisId);                     
                        // overwrite with "empty" value to signal deletion
                        t.write(oldValueToIdKey, DELETED_RECORD_VALUE);
                    }
                    
                    // store the new value
                    if (!fieldValue.isEmpty()) {
                        t.write(idToValueKey, fieldValue);
                        t.write(valueToIdKey, objectStringIdentity);
                    }
                }
            }
        }
    }
    
    private static void removeObjectFromUniqueMemberKey(ObjectProvider op, Transaction t) 
            throws ConnectionException, ClassCastException, UnknownException {
        AbstractClassMetaData cmd = op.getClassMetaData();
        String objectStringIdentity = getPersistableIdentity(op);
        String className = cmd.getFullClassName();
        
        for (int field : cmd.getAllMemberPositions()) {
            AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(field);
            UniqueMetaData umd = mmd.getUniqueMetaData();
            if (umd != null) {
                // this member has @Unique annotation -> lookup all stored values for this member
                String fieldName = mmd.getName();

                String idToValueKey = geIdToUniqueMemberValueKeyName(objectStringIdentity, fieldName);
                String oldValueByThisId = null;
                try {
                    oldValueByThisId = t.read(idToValueKey).stringValue();
                } catch (NotFoundException e) {
                    // should not happen but is not breaking anything
                }
                
                if (oldValueByThisId != null && !oldValueByThisId.isEmpty()) {
                    String valueToIdKey = getUniqueMemberValueToIdKeyName(className, fieldName, oldValueByThisId);
                    t.write(valueToIdKey, DELETED_RECORD_VALUE);
                }
                t.write(idToValueKey, DELETED_RECORD_VALUE);
            }
        }
    }
    
    /**
     * Scalaris does not support deletion (in a usable way). Therefore, deletion
     * is simulated by overwriting an object with a "deleted" value.
     * 
     * This method returns true if json is a json of a deleted record.
     * 
     * @param record
     * @return
     */
    public static boolean isDeletedRecord(final JSONObject record) {
        return record == null || isDeletedRecord(record.toString());
    }
    
    public static boolean isDeletedRecord(final String record) {
        return record == null || record.isEmpty() || record.equals(DELETED_RECORD_VALUE);
    }
    
    /**
     * Convenience method to get all objects of the candidate type from the
     * specified connection. Objects of subclasses are ignored.
     * 
     * @param ec
     * @param mconn
     * @param candidateClass
     */
    public static List<Object> getObjectsOfCandidateType(ExecutionContext ec,
            ManagedConnection mconn, Class<?> candidateClass,
            AbstractClassMetaData cmd) {
        List<Object> results = new ArrayList<Object>();
        String managementKey = getManagementKeyName(candidateClass);

        de.zib.scalaris.Connection conn = (de.zib.scalaris.Connection) mconn
                .getConnection();

        try {
            // read the management key
            Transaction t = new Transaction(conn);
            JSONArray json = new JSONArray(t.read(managementKey).stringValue());

            // retrieve all values from the management key
            for (int i = 0; i < json.length(); i++) {
                String s = json.getString(i);
                results.add(IdentityUtils.getObjectFromPersistableIdentity(s,cmd, ec));
            }

            t.commit();
        } catch (NotFoundException e) {
            // the management key does not exist which means there
            // are no instances of this class stored.
        } catch (ConnectionException e) {
            throw new NucleusException(e.getMessage(), e);
        } catch (AbortException e) {
            throw new NucleusException(e.getMessage(), e);
        } catch (UnknownException e) {
            throw new NucleusException(e.getMessage(), e);
        } catch (JSONException e) {
            // management key has invalid format
            throw new NucleusException(e.getMessage(), e);
        }
        return results;
    }
}