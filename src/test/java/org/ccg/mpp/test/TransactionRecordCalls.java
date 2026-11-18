package org.ccg.mpp.test;

import java.util.List;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.data.admin.AdminTransactionEntry;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.TxCommitLog;
import org.mockito.invocation.InvocationOnMock;

public final class TransactionRecordCalls {
    public static final String WRITE = "writeTransactionRecords";

    private TransactionRecordCalls() {
    }

    public static boolean isWrite(InvocationOnMock invocation) {
        return WRITE.equals(invocation.getMethod().getName());
    }

    public static List<AdminTransactionEntry> insertsOf(InvocationOnMock invocation) {
        return invocation.getArgument(0);
    }

    public static List<String> deletesOf(InvocationOnMock invocation) {
        return invocation.getArgument(1);
    }

    public static boolean inserts(InvocationOnMock invocation, String recordId) {
        return isWrite(invocation) && insertsOf(invocation).stream().anyMatch(entry -> entry.get_id().equals(recordId));
    }

    public static boolean deletes(InvocationOnMock invocation, String recordId) {
        return isWrite(invocation) && deletesOf(invocation).contains(recordId);
    }

    public static boolean clearsLocalCommit(InvocationOnMock invocation, String txId) {
        return deletes(invocation, TxCommitLog.markerId(txId));
    }

    public static boolean writesLocalCommit(InvocationOnMock invocation, String txId) {
        return inserts(invocation, TxCommitLog.markerId(txId));
    }

    public static void persistBufferedOps(Transaction transaction) throws Exception {
        if (!transaction.areOpsDurable()) {
            AdminOperationHelper.writeTransactionRecords(transaction.getPendingOps(), List.of());
            transaction.markOpsDurable();
        }
    }
}
