package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.core.utilities.UntrustworthyData
import net.corda.core.utilities.unwrap

private class SendTransactionFlow(private val otherSide: Party, private var payload: Any?,
                                  private val transactionAccessControl: (SecureHash) -> Boolean = { true },
                                  private val attachmentAccessControl: (SecureHash) -> Boolean = { true }) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        // Expect fetch data request until received a end request.
        while (true) {
            val message = payload?.let { sendAndReceive<FetchDataFlow.Request>(otherSide, it) } ?: receive<FetchDataFlow.Request>(otherSide)
            val request = message.unwrap {
                if (it !is EndDataRequest && it.hashes.isEmpty()) throw FlowException("Empty hash list")
                // TODO: Check requested hash is relevant to the flow?
                it
            }
            payload = when (request) {
                is FetchTransactionsRequest -> request.hashes.filter(transactionAccessControl).map { serviceHub.validatedTransactions.getTransaction(it) ?: throw FetchDataFlow.HashNotFound(it) }
                is FetchAttachmentsRequest -> request.hashes.filter(attachmentAccessControl).map { serviceHub.attachments.openAttachment(it)?.open()?.readBytes() ?: throw FetchDataFlow.HashNotFound(it) }
                is EndDataRequest -> return
                else -> throw FlowException("Unsupported Fetch Data Request : ${request.javaClass}")
            }
        }
    }
}

@Suspendable
inline fun <reified R : Any> FlowLogic<*>.sendAndReceiveWithDataVending(otherSide: Party, payload: Any): UntrustworthyData<R> = sendAndReceiveWithDataVending(R::class.java, otherSide, payload)

@Suspendable
inline fun <reified R : Any> FlowLogic<*>.receiveWithDataVending(otherSide: Party): UntrustworthyData<R> = sendAndReceiveWithDataVending(R::class.java, otherSide, null)

@Suspendable
fun <R : Any> FlowLogic<*>.sendAndReceiveWithDataVending(receiveType: Class<R>, otherSide: Party, payload: Any?): UntrustworthyData<R> {
    subFlow(SendTransactionFlow(otherSide, payload))
    return receive(receiveType, otherSide)
}

@Suspendable
fun FlowLogic<*>.sendWithDataVending(otherSide: Party, payload: Any) = subFlow(SendTransactionFlow(otherSide, payload))

@Suspendable
fun FlowLogic<*>.startDataVending(otherSide: Party) = subFlow(SendTransactionFlow(otherSide, null))