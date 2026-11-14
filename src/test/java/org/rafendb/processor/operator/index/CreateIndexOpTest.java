package org.rafendb.processor.operator.index;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.mock.concurrency.MockConcurrencyControl;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.txn.AutoCommit;
import org.rafendb.processor.txn.TransactionCoordinator;

class CreateIndexOpTest {
    private MockStorageManager delegate;
    private StorageManager storage;
    private MockConcurrencyControl ccm;
    private TransactionCoordinator coordinator;
    private ExecutionContext context;

    private final List<IndexInfo> createdIndexes = new ArrayList<>();
    private StorageInternalException injectedFailure;

    @BeforeEach
    void setUp() {
        delegate = new MockStorageManager();
        ccm = new MockConcurrencyControl();

        createdIndexes.clear();
        injectedFailure = null;

        storage =
                (StorageManager)
                        Proxy.newProxyInstance(
                                StorageManager.class.getClassLoader(),
                                new Class<?>[] {StorageManager.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("createIndex")) {

                                        if (injectedFailure != null) {
                                            throw injectedFailure;
                                        }

                                        IndexInfo index = (IndexInfo) args[0];

                                        try {
                                            method.invoke(delegate, args);
                                            createdIndexes.add(index);
                                            return null;

                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    }

                                    try {
                                        return method.invoke(delegate, args);

                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });

        SessionContext session = new SessionContext(1);

        coordinator = new TransactionCoordinator(session, ccm, storage, null);

        context = coordinator.getExecutionContext();
    }

    private CreateIndexPlanNode createNode(
            String indexName, String columnName, CreateIndexPlanNode.IndexKind kind) {
        return new CreateIndexPlanNode(
                indexName, "mahasiswa", new Expr.ColumnRef("mahasiswa", columnName), kind);
    }

    @Test
    void shouldCreateBTreeIndexSuccessfully() {
        // Arrange
        CreateIndexPlanNode node =
                createNode("idx_mahasiswa_nama_btree", "nama", CreateIndexPlanNode.IndexKind.BTREE);

        CreateIndexOp operation = new CreateIndexOp(context, node);

        // Act
        // Act
        ExecutionPlan.CommandResult result;

        try {
            result = AutoCommit.run(coordinator, operation::execute);
        } catch (ConcurrencyInternalException e) {
            throw new RuntimeException(e);
        }

        // Assert
        assertNotNull(result);
        assertEquals(0, result.affectedRows());

        assertEquals(1, createdIndexes.size());

        IndexInfo expected =
                new IndexInfo(
                        "idx_mahasiswa_nama_btree", "mahasiswa", "nama", IndexType.B_PLUS_TREE);

        assertEquals(expected, createdIndexes.get(0));
    }

    @Test
    void shouldCreateHashIndexSuccessfully() {
        // Arrange
        CreateIndexPlanNode node =
                createNode("idx_mahasiswa_nama_hash", "nama", CreateIndexPlanNode.IndexKind.HASH);

        CreateIndexOp operation = new CreateIndexOp(context, node);

        // Act
        ExecutionPlan.CommandResult result;

        try {
            result = AutoCommit.run(coordinator, operation::execute);
        } catch (ConcurrencyInternalException e) {
            throw new RuntimeException(e);
        }
        // Assert
        assertNotNull(result);
        assertEquals(0, result.affectedRows());

        assertEquals(1, createdIndexes.size());

        IndexInfo expected =
                new IndexInfo("idx_mahasiswa_nama_hash", "mahasiswa", "nama", IndexType.HASH);

        assertEquals(expected, createdIndexes.get(0));
    }

    @Test
    void shouldPropagateStorageRuntimeException() {
        // Arrange
        CreateIndexPlanNode node =
                createNode(
                        "idx_invalid_column",
                        "unknown_column",
                        CreateIndexPlanNode.IndexKind.BTREE);

        CreateIndexOp operation = new CreateIndexOp(context, node);

        // Act
        StorageRuntimeException thrown =
                assertThrows(
                        StorageRuntimeException.class,
                        () -> AutoCommit.run(coordinator, operation::execute));

        // Assert
        assertNotNull(thrown);
        assertTrue(thrown.getMessage().contains("unknown column"));

        assertTrue(createdIndexes.isEmpty());
    }

    @Test
    void shouldWrapStorageInternalException() {
        // Arrange
        CreateIndexPlanNode node =
                createNode(
                        "idx_mahasiswa_nama_internal", "nama", CreateIndexPlanNode.IndexKind.BTREE);

        injectedFailure = new StorageInternalException("Simulated storage failure");

        CreateIndexOp operation = new CreateIndexOp(context, node);

        // Act
        QueryExecutionException thrown =
                assertThrows(
                        QueryExecutionException.class,
                        () -> AutoCommit.run(coordinator, operation::execute));
        // Assert
        assertNotNull(thrown.getCause());

        assertInstanceOf(StorageInternalException.class, thrown.getCause());

        assertTrue(thrown.getCause().getMessage().contains("Simulated storage failure"));
        assertTrue(createdIndexes.isEmpty());
    }
}
