/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.iv2;

import java.io.IOException;
import java.util.ArrayList;

import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import org.voltcore.logging.Level;
import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.Mailbox;
import org.voltcore.utils.CoreUtils;
import org.voltdb.ClientResponseImpl;
import org.voltdb.SiteProcedureConnection;
import org.voltdb.VoltTable;
import org.voltdb.client.ClientResponse;
import org.voltdb.messaging.CompleteTransactionMessage;
import org.voltdb.messaging.InitiateResponseMessage;
import org.voltdb.messaging.Iv2InitiateTaskMessage;
import org.voltdb.rejoin.TaskLog;
import org.voltdb.utils.LogKeys;

/**
 * Implements the Multi-partition procedure ProcedureTask.
 * Runs multi-partition transaction, causing work to be distributed
 * across the cluster as necessary.
 */
public class MpProcedureTask extends ProcedureTask
{
    private static final VoltLogger log = new VoltLogger("HOST");

    final List<Long> m_initiatorHSIds = new ArrayList<Long>();
    // Need to store the new masters list so that we can update the list of masters
    // when we requeue this Task to for restart
    final private AtomicReference<List<Long>> m_restartMasters = new AtomicReference<List<Long>>();
    boolean m_isRestart = false;
    final Iv2InitiateTaskMessage m_msg;

    MpProcedureTask(Mailbox mailbox, String procName, TransactionTaskQueue queue,
                  Iv2InitiateTaskMessage msg, List<Long> pInitiators,
                  long buddyHSId, boolean isRestart)
    {
        super(mailbox, procName,
              new MpTransactionState(mailbox, msg, pInitiators,
                                     buddyHSId, isRestart),
              queue);
        m_isRestart = isRestart;
        m_msg = msg;
        m_initiatorHSIds.addAll(pInitiators);
        m_restartMasters.set(new ArrayList<Long>());
    }

    /**
     * Update the list of partition masters in the event of a failure/promotion.
     * Currently only thread-"safe" by virtue of only calling this on
     * MpProcedureTasks which are not at the head of the MPI's TransactionTaskQueue.
     */
    public void updateMasters(List<Long> masters)
    {
        m_initiatorHSIds.clear();
        m_initiatorHSIds.addAll(masters);
        ((MpTransactionState)getTransactionState()).updateMasters(masters);
    }

    /**
     * Update the list of partition masters to be used when this transaction is restarted.
     * Currently thread-safe because we call this before poisoning the MP
     * Transaction to restart it, and only do this sequentially from the
     * repairing thread.
     */
    public void doRestart(List<Long> masters)
    {
        List<Long> copy = new ArrayList<Long>(masters);
        m_restartMasters.set(copy);
    }

    /** Run is invoked by a run-loop to execute this transaction. */
    @Override
    public void run(SiteProcedureConnection siteConnection)
    {
        hostLog.debug("STARTING: " + this);
        // Cast up. Could avoid ugliness with Iv2TransactionClass baseclass
        MpTransactionState txn = (MpTransactionState)m_txn;
        // Check for restarting sysprocs
        if (m_isRestart &&
            (txn.m_task.getStoredProcedureName().startsWith("@") &&
             !txn.m_task.getStoredProcedureName().startsWith("@AdHoc"))) {
            InitiateResponseMessage errorResp = new InitiateResponseMessage(txn.m_task);
            errorResp.setResults(new ClientResponseImpl(ClientResponse.UNEXPECTED_FAILURE,
                        new VoltTable[] {},
                        "Failure while running system procedure " + txn.m_task.getStoredProcedureName() +
                        ", and system procedures can not be restarted."));
            txn.setNeedsRollback();
            completeInitiateTask(siteConnection);
            errorResp.m_sourceHSId = m_initiator.getHSId();
            m_initiator.deliver(errorResp);
            hostLog.debug("SYSPROCFAIL: " + this);
            return;
        }

        // Let's ensure that we flush any previous attempts of this transaction
        // at the masters we're going to try to use this time around.
        if (m_isRestart) {
            CompleteTransactionMessage restart = new CompleteTransactionMessage(
                    m_initiator.getHSId(), // who is the "initiator" now??
                    m_initiator.getHSId(),
                    m_txn.txnId,
                    m_txn.isReadOnly(),
                    0,
                    true,
                    false,  // really don't want to have ack the ack.
                    !m_txn.isReadOnly(),
                    m_msg.isForReplay());

            restart.setTruncationHandle(m_msg.getTruncationHandle());
            restart.setOriginalTxnId(m_msg.getOriginalTxnId());
            m_initiator.send(com.google.common.primitives.Longs.toArray(m_initiatorHSIds), restart);
        }
        final InitiateResponseMessage response = processInitiateTask(txn.m_task, siteConnection);
        // We currently don't want to restart read-only MP transactions because:
        // 1) We're not writing the Iv2InitiateTaskMessage to the first
        // FragmentTaskMessage in read-only case in the name of some unmeasured
        // performance impact,
        // 2) We don't want to perturb command logging and/or DR this close to the 3.0 release
        // 3) We don't guarantee the restarted results returned to the client
        // anyway, so not restarting the read is currently harmless.
        // We could actually restart this here, since we have the invocation, but let's be consistent?
        int status = response.getClientResponseData().getStatus();
        if (status != ClientResponse.TXN_RESTART || (status == ClientResponse.TXN_RESTART && m_msg.isReadOnly())) {
            if (!response.shouldCommit()) {
                txn.setNeedsRollback();
            }
            completeInitiateTask(siteConnection);
            // Set the source HSId (ugh) to ourselves so we track the message path correctly
            response.m_sourceHSId = m_initiator.getHSId();
            m_initiator.deliver(response);
            execLog.l7dlog( Level.TRACE, LogKeys.org_voltdb_ExecutionSite_SendingCompletedWUToDtxn.name(), null);
            hostLog.debug("COMPLETE: " + this);
        }
        else {
            restartTransaction();
            hostLog.debug("RESTART: " + this);
        }
    }

    @Override
    public void runForRejoin(SiteProcedureConnection siteConnection, TaskLog taskLog)
    throws IOException
    {
        throw new RuntimeException("MP procedure task asked to run on rejoining site.");
    }

    @Override
    public void runFromTaskLog(SiteProcedureConnection siteConnection)
    {
        throw new RuntimeException("MP procedure task asked to run from tasklog on rejoining site.");
    }

    @Override
    void completeInitiateTask(SiteProcedureConnection siteConnection)
    {
        CompleteTransactionMessage complete = new CompleteTransactionMessage(
                m_initiator.getHSId(), // who is the "initiator" now??
                m_initiator.getHSId(),
                m_txn.txnId,
                m_txn.isReadOnly(),
                m_txn.getHash(),
                m_txn.needsRollback(),
                false,  // really don't want to have ack the ack.
                false,
                m_msg.isForReplay());

        complete.setTruncationHandle(m_msg.getTruncationHandle());
        complete.setOriginalTxnId(m_msg.getOriginalTxnId());
        m_initiator.send(com.google.common.primitives.Longs.toArray(m_initiatorHSIds), complete);
        m_txn.setDone();
        m_queue.flush();
    }

    private void restartTransaction()
    {
        // We don't need to send restart messages here; the next SiteTasker
        // which will run on the MPI's Site thread will be the repair task,
        // which will send the necessary CompleteTransactionMessage to restart.
        ((MpTransactionState)m_txn).restart();
        // Update the masters list with the list provided when restart was triggered
        updateMasters(m_restartMasters.get());
        m_isRestart = true;
        m_queue.restart();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("MpProcedureTask:");
        sb.append("  TXN ID: ").append(TxnEgo.txnIdToString(getTxnId()));
        sb.append("  SP HANDLE ID: ").append(TxnEgo.txnIdToString(getSpHandle()));
        sb.append("  ON HSID: ").append(CoreUtils.hsIdToString(m_initiator.getHSId()));
        return sb.toString();
    }
}
