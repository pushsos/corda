package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.core.utilities.UntrustworthyData
import net.corda.core.utilities.unwrap

/**
 * The [SendDataFlow] provide ad hoc data vending service, which anticipate data request from the [otherSide] during
 * transaction resolving process.
 *
 * @param otherSide the target party.
 * @param requestVerifier optional verifier to spot potential malicious data request, the [requestVerifier] can have additional
 * checks to make sure data request is relevant to the flow.
 */
class SendDataFlow(private val otherSide: Party,
                   private val requestVerifier: (FetchDataFlow.Request) -> Boolean = { true }) : AbstractSendDataFlow() {

    override fun getRequest(payload: Any?) = payload?.let { sendAndReceive<FetchDataFlow.Request>(otherSide, it) } ?: receive<FetchDataFlow.Request>(otherSide)
    override fun verifyRequest(request: FetchDataFlow.Request) = require(requestVerifier(request))
}

/**
 * This send data flow is intended to use by the Notary client.
 */
internal class SendDataWithRetry(private val otherSide: Party, initialPayload: Any) : AbstractSendDataFlow(initialPayload) {
    override fun getRequest(payload: Any?) = sendAndReceiveWithRetry<FetchDataFlow.Request>(otherSide, payload!!)
}

abstract class AbstractSendDataFlow(private var payload: Any? = null) : FlowLogic<Unit>() {
    protected abstract fun getRequest(payload: Any?): UntrustworthyData<FetchDataFlow.Request>
    protected open fun verifyRequest(request: FetchDataFlow.Request) {}

    @Suspendable
    override fun call() {
        // Expect fetch data request until received an end request.
        while (true) {
            val request = getRequest(payload).unwrap {
                if (it !is EndDataRequest) {
                    if (it.hashes.isEmpty()) throw FlowException("Empty hash list")
                    verifyRequest(it)
                }
                it
            }
            payload = when (request) {
                is FetchTransactionsRequest -> request.hashes.map {
                    serviceHub.validatedTransactions.getTransaction(it) ?: throw FetchDataFlow.HashNotFound(it)
                }
                is FetchAttachmentsRequest -> request.hashes.map {
                    serviceHub.attachments.openAttachment(it)?.open()?.readBytes() ?: throw FetchDataFlow.HashNotFound(it)
                }
                is EndDataRequest -> return
                else -> throw FlowException("Unsupported Fetch Data Request : $request")
            }
        }
    }
}