package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.core.utilities.unwrap

class SendTransactionFlow(private val otherSide: Party,
                          private val transactionAccessControl: (SecureHash) -> Boolean = { true },
                          private val attachmentAccessControl: (SecureHash) -> Boolean = { true }) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        // Expect fetch data request until received a end message.
        while (true) {
            val request = receive<FetchDataFlow.Request>(otherSide).unwrap {
                // TODO: validate party?
                it
            }
            val response = when (request) {
                is FetchTransactionsFlow.Request -> request.hashes.filter(transactionAccessControl).map { serviceHub.validatedTransactions.getTransaction(it) ?: throw FetchDataFlow.HashNotFound(it) }
                is FetchAttachmentsFlow.Request -> request.hashes.filter(attachmentAccessControl).map { serviceHub.attachments.openAttachment(it)?.open()?.readBytes() ?: throw FetchDataFlow.HashNotFound(it) }
                is FetchDataFlow.EndRequest -> return
                else -> throw FlowException("Unsupported Fetch Data Request : ${request.javaClass}")
            }
            send(otherSide, response)
        }
    }
}
