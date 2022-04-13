/*
 * Copyright 2015 Bizosys Technologies Limited
 *
 * Licensed to the Bizosys Technologies Limited (Bizosys) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The Bizosys licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package farm.nurture.laminar.core.sql.dao;

import static farm.nurture.laminar.core.sql.dao.Constants.COLUMNS_NULL_MSG;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WriteBase {

    private static final Logger LOG = LoggerFactory.getLogger(WriteBase.class);
    private static final Object[] EMPTY_ARRAY = new Object[] {};
    static volatile int transactions = 0;
    static Exception lastBegin = null;
    protected int recordsTouched = -1;
    protected boolean isInTransaction = false;
    private Connection con = null;
    private PreparedStatement prepareStmt = null;
    private ResultSet rs = null;
    private String lastGeneratedKey = null;
    private IPool conPool = null;

    public WriteBase() {
        conPool = PoolFactory.getDefaultPool();
    }

    public WriteBase(String poolName) {
        conPool = PoolFactory.getInstance().getPool(poolName, false);
    }

    // Only time to create a connection if not there.
    public void beginTransaction() throws SQLException {

        if (LOG.isDebugEnabled()) {
            try {
                throw new IOException("Transaction Count: " + transactions);
            } catch (Exception ex) {
                if (transactions > 0 && lastBegin != null)
                    LOG.warn("Transaction leaked, possible table locking", lastBegin);
                lastBegin = ex;
            }
            transactions++;
        }

        /**
         * If already an open temporary transaction then set auto commit false. else create a connection
         * with auto commit false
         */
        if (this.isInTransaction) {
            this.con.setAutoCommit(false);
        } else {
            this.createConnection(false);
            this.isInTransaction = true;
            if (LOG.isDebugEnabled())
                LOG.debug("Beginning Transaction {} inTransaction = {}", this.hashCode(), transactions);
        }
    }

    // End the transaction now.
    public void commitTransaction(boolean isOpenTempTransaction) throws SQLException {
        if (LOG.isDebugEnabled()) transactions--;

        /**
         * If already an open temporary transaction then isOpenTempTransaction = true. else
         * isOpenTempTransaction = false
         */
        if (this.isInTransaction) {
            this.isInTransaction = isOpenTempTransaction;
            this.con.commit();
            this.releaseResources();
            if (LOG.isDebugEnabled())
                LOG.debug("Committed Transaction {}  intransaction = {}", this.hashCode(), transactions);
        }
    }

    public void commitTransaction() throws SQLException {
        commitTransaction(false);
    }

    // Rollback the transaction now.
    public void rollbackTransaction() {
        if (LOG.isDebugEnabled() && transactions > 0) transactions--;
        if (this.isInTransaction) {
            this.isInTransaction = false;
            if (LOG.isDebugEnabled()) LOG.debug("Rolling back the connection {}", this.hashCode());
            try {
                this.con.rollback();

            } catch (SQLException ex) {
                LOG.error("Rollback failed", ex);

            } finally {
                this.releaseResources();
            }
        }
    }

    // If the transaction is not there, create it.
    protected void createConnection(boolean autoCommit) throws SQLException {
        if (!this.isInTransaction) {
            this.con = conPool.getConnection();
            this.con.setAutoCommit(autoCommit);
        }
    }

    /**
     * Use case: Create temp table and use the select clause on the temp tables created so for above
     * scenarion preserve connections across queries and finally call the close temp transaction.
     *
     * @throws SQLException
     */
    public void openTempTransaction() throws SQLException {
        this.createConnection(true);
        this.isInTransaction = true;
    }

    public void closeTempTransaction() {
        this.isInTransaction = false;
        this.releaseResources();
    }

    public void setReadOnTxnScope(ReadBase<?> r) {
        r.con = this.con;
        r.isTxn = true;
    }

    // If the transaction is not there, release connection.
    protected void releaseResources() {
        if (this.isInTransaction) {
            if (LOG.isDebugEnabled())
                LOG.debug("Releasing statement and resultset only {}", this.hashCode());
            this.release(true, true, false);
        } else {
            if (LOG.isDebugEnabled())
                LOG.debug("Releasing connection, statement and resultset {}", this.hashCode());
            this.release(true, true, true);
        }
    }

    // The insert statement
    public String insert(String query, Object[] columns) throws SQLException {

        boolean sucess = false;
        lastGeneratedKey = null;

        try {
            this.createConnection(true);
            this.prepareStmt = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);

            int columnsT = (null == columns) ? 0 : columns.length;
            if (null == columns) {
                throw new RuntimeException(COLUMNS_NULL_MSG);
            }
            for (int i = 1; i <= columnsT; i++) {
                if (LOG.isDebugEnabled()) LOG.debug("{}-{}", i, columns[i - 1]);
                this.prepareStmt.setObject(i, columns[i - 1]);
            }
            this.recordsTouched = prepareStmt.executeUpdate();
            if (LOG.isDebugEnabled())
                LOG.debug("Records Touched= {}-{}", this.recordsTouched, this.hashCode());
            if (this.recordsTouched > 0) {
                processRecord();
            }
            sucess = true;
            return lastGeneratedKey;
        } catch (SQLException ex) {
            logException(query, columns, ex);
            throw ex;
        } finally {
            this.releaseResources();
        }
    }

    private void processRecord() throws SQLException {
        rs = prepareStmt.getGeneratedKeys();
        if (null != rs && rs.next()) {
            Object keyO = rs.getObject(1);
            if (null != keyO) lastGeneratedKey = keyO.toString();
        } else {
            throw new SQLException("NO_KEY_GENERATED");
        }
    }

    public String insert(String query, List<Object> columns) throws SQLException {

        boolean sucess = false;
        lastGeneratedKey = null;

        try {
            this.createConnection(true);
            this.prepareStmt = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);

            int colsT = (null == columns) ? 0 : columns.size();
            if (null == columns) {
                throw new RuntimeException(COLUMNS_NULL_MSG);
            }
            for (int i = 1; i <= colsT; i++) {

                this.prepareStmt.setObject(i, columns.get(i - 1));
            }

            this.recordsTouched = prepareStmt.executeUpdate();
            if (LOG.isDebugEnabled())
                LOG.debug("Records Touched= {}-{}", this.recordsTouched, this.hashCode());

            if (this.recordsTouched > 0) {
                processRecord();
            }
            sucess = true;
            return lastGeneratedKey;
        } catch (SQLException ex) {
            logException(query, columns, ex);
            throw ex;
        } finally {
            this.releaseResources();
        }
    }

    public int execute(String query) throws SQLException {

        boolean sucess = false;

        try {
            int records = execute(query, EMPTY_ARRAY);
            sucess = true;
            return records;
        } catch (Exception ex) {
            throw new SQLException(ex);
        } finally {
        }
    }

    public int execute(String query, Object[] columns) throws SQLException {

        boolean sucess = false;
        lastGeneratedKey = null;

        try {
            this.createConnection(true);
            this.prepareStmt = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);

            int columnsT = (null == columns) ? 0 : columns.length;

            if (null == columns) {
                throw new RuntimeException(COLUMNS_NULL_MSG);
            }

            for (int i = 1; i <= columnsT; i++) {
                this.prepareStmt.setObject(i, columns[i - 1]);
            }

            this.recordsTouched = prepareStmt.executeUpdate();
            if (this.recordsTouched > 0) {
                rs = prepareStmt.getGeneratedKeys();
                if (null != rs && rs.next()) {
                    Object keyO = rs.getObject(1);
                    if (null != keyO) lastGeneratedKey = keyO.toString();
                }
            }
            sucess = true;
            return this.recordsTouched;

        } catch (SQLException ex) {

            logException(query, columns, ex);
            throw ex;

        } finally {
            this.releaseResources();
        }
    }

    public int execute(String query, List<Object> columns) throws SQLException {
        int colsT = 0;
        boolean sucess = false;

        try {
            this.createConnection(true);
            this.prepareStmt = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);

            colsT = (null == columns) ? 0 : columns.size();

            if (null == columns) {
                throw new RuntimeException(COLUMNS_NULL_MSG);
            }

            for (int i = 1; i <= colsT; i++) {
                this.prepareStmt.setObject(i, columns.get(i - 1));
            }
            this.recordsTouched = prepareStmt.executeUpdate();
            if (this.recordsTouched > 0) {
                rs = prepareStmt.getGeneratedKeys();
                if (null != rs && rs.next()) {
                    Object keyO = rs.getObject(1);
                    if (null != keyO) lastGeneratedKey = keyO.toString();
                }
            }

            sucess = true;
            return this.recordsTouched;

        } catch (ArrayIndexOutOfBoundsException ex) {
            logException(query, columns, ex);
            throw new SQLException(
                "Not able to map the preprated stmts : " + query + "\nTotal Cols: " + colsT);

        } catch (SQLException ex) {
            logException(query, columns, ex);
            throw ex;

        } finally {
            this.releaseResources();
        }
    }

    private void logException(String query, Object[] columns, SQLException ex) {
        StringBuilder sb = new StringBuilder(256);
        if (null != columns) {
            for (Object column : columns) {
                sb.append('[').append(column).append(']');
            }
        }
        LOG.error("Query = " + query.replace('\n', ' ') + "\nColums = " + sb.toString(), ex);
        ex.printStackTrace();
    }

    private void logException(String query, List<Object> columns, Exception ex) {
        StringBuilder sb = new StringBuilder(256);
        if (null != columns) {
            for (Object column : columns) {
                sb.append('[').append(column).append(']');
            }
        }
        LOG.error("Query = " + query.replace('\n', ' ') + "\nColums = " + sb.toString(), ex);
        ex.printStackTrace();
    }

    protected void release(boolean closeRs, boolean closeStmt, boolean closeCon) {
        if (this.rs != null && closeRs) {
            try {
                this.rs.close();
                this.rs = null;
            } catch (SQLException ex) {
                LOG.error("Release Failed, Possible memory leak", ex);
            }
        }
        if (this.prepareStmt != null && closeStmt) {
            try {
                this.prepareStmt.close();
                this.prepareStmt = null;
            } catch (SQLException ex) {
                LOG.error("Unable to release prepared statement", ex);
            }
        }

        if (this.con != null && closeCon) {
            try {
                this.con.close();
                this.con = null;
            } catch (SQLException ex) {
                LOG.error("Unable to release prepared connection", ex);
            }
        }
    }

    /**
     * To execute a batch of updates or inserts onto a single table.
     *
     * @param query - For all records to execute with
     * @param records - List of column values for each record. List of Object[] objects
     * @return int[] - rows updated per record
     * @throws SQLException
     */
    public BatchResponse executeBatch(String query, List<Object[]> records) throws SQLException {
        boolean transactionByBatch = !(this.isInTransaction);

        if (null == records) {
            if (transactionByBatch) this.commitTransaction();
            return new BatchResponse(new int[] {}, new String[] {}, 0);
        } else {
            if (LOG.isDebugEnabled()) LOG.debug("Number of records to executeBatch : {}", records.size());
        }

        this.startBatch(query, transactionByBatch);
        for (Object[] currRecord : records) {
            this.addToBatch(currRecord);
        }
        return this.executeBatch(transactionByBatch);
    }

    private void startBatch(String query, boolean transactionByBatch) throws SQLException {
        try {
            if (transactionByBatch) this.beginTransaction();
            this.createConnection(true);
            this.prepareStmt = this.con.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
        } catch (SQLException ex) {
            LOG.error("Unable to start batch", ex);
            throw ex;
        }
    }

    private void addToBatch(Object[] columns) throws SQLException {
        if (this.prepareStmt == null) {
            throw new SQLException("Illegal call. startBatch has to be done before addToBatch");
        }

        int columnsT = (null == columns) ? 0 : columns.length;

        if (null == columns) {
            throw new RuntimeException(COLUMNS_NULL_MSG);
        }

        for (int i = 1; i <= columnsT; i++) {
            this.prepareStmt.setObject(i, columns[i - 1]);
        }
        this.prepareStmt.addBatch();
    }

    private BatchResponse executeBatch(boolean transactionByBatch) throws SQLException {
        if (this.prepareStmt == null) {
            throw new SQLException("Illegal call. startBatch has to be done before addToBatch");
        }
        try {
            // an array of update counts
            int[] updatedIdCount = this.prepareStmt.executeBatch();

            int totalIdsT = 0;
            String[] generatedIds = null;
            for (int updateCount : updatedIdCount) totalIdsT = totalIdsT + updateCount;
            if (totalIdsT > 0) {
                generatedIds = getStrings(totalIdsT, generatedIds);
            }
            BatchResponse res = new BatchResponse(updatedIdCount, generatedIds, totalIdsT);

            if (transactionByBatch) this.commitTransaction();
            return res;
        } catch (SQLException ex) {
            LOG.error("Unable to execute batch", ex);
            if (transactionByBatch) this.rollbackTransaction();
            throw ex;
        } finally {
            this.releaseResources();
        }
    }

    private String[] getStrings(int totalIdsT, String[] generatedIds) throws SQLException {
        rs = this.prepareStmt.getGeneratedKeys();
        if (null != rs) {
            generatedIds = new String[totalIdsT];
            Arrays.fill(generatedIds, null);
            int index = 0;
            while (rs.next()) {
                Object keyO = rs.getObject(1);
                if (null != keyO) generatedIds[index] = keyO.toString();
                index++;
            }
        }
        return generatedIds;
    }

    @Setter
    @Getter
    public static class BatchResponse {
        private int[] updateCounts;
        private String[] generatedIds;
        private int total = 0;

        public BatchResponse(int[] updateCounts, String[] generatedIds, int total) {
            setUpdateCounts(updateCounts);
            setGeneratedIds(generatedIds);
            setTotal(total);
        }
    }
}
