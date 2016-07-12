package com.r3corda.node.services

import com.r3corda.core.contracts.Command
import com.r3corda.core.contracts.DummyContract
import com.r3corda.core.contracts.TransactionType
import com.r3corda.core.testing.DUMMY_NOTARY
import com.r3corda.core.testing.DUMMY_NOTARY_KEY
import com.r3corda.core.testing.MEGA_CORP_KEY
import com.r3corda.core.testing.MINI_CORP_KEY
import com.r3corda.node.internal.testing.MockNetwork
import com.r3corda.node.services.network.NetworkMapService
import com.r3corda.node.services.transactions.ValidatingNotaryService
import com.r3corda.protocols.NotaryError
import com.r3corda.protocols.NotaryException
import com.r3corda.protocols.NotaryProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidatingNotaryServiceTests {
    lateinit var net: MockNetwork
    lateinit var notaryNode: MockNetwork.MockNode
    lateinit var clientNode: MockNetwork.MockNode

    @Before fun setup() {
        net = MockNetwork()
        notaryNode = net.createNode(
                legalName = DUMMY_NOTARY.name,
                keyPair = DUMMY_NOTARY_KEY,
                advertisedServices = *arrayOf(NetworkMapService.Type, ValidatingNotaryService.Type)
        )
        clientNode = net.createNode(networkMapAddress = notaryNode.info, keyPair = MINI_CORP_KEY)
        net.runNetwork() // Clear network map registration messages
    }

    @Test fun `should report error for invalid transaction dependency`() {
        val stx = run {
            val inputState = issueInvalidState(clientNode)
            val tx = TransactionType.General.Builder().withItems(inputState)
            tx.signWith(clientNode.keyPair!!)
            tx.toSignedTransaction(false)
        }

        val protocol = NotaryProtocol.Client(stx)
        val future = clientNode.smm.add(NotaryProtocol.TOPIC, protocol)
        net.runNetwork()

        val ex = assertFailsWith(ExecutionException::class) { future.get() }
        val notaryError = (ex.cause as NotaryException).error
        assertThat(notaryError).isInstanceOf(NotaryError.TransactionInvalid::class.java)
    }

    @Test fun `should report error for missing signatures`() {
        val expectedMissingKey = MEGA_CORP_KEY.public
        val stx = run {
            val inputState = issueState(clientNode)

            val command = Command(DummyContract.Commands.Move(), expectedMissingKey)
            val tx = TransactionType.General.Builder().withItems(inputState, command)
            tx.signWith(clientNode.keyPair!!)
            tx.toSignedTransaction(false)
        }

        val protocol = NotaryProtocol.Client(stx)
        val future = clientNode.smm.add(NotaryProtocol.TOPIC, protocol)
        net.runNetwork()

        val ex = assertFailsWith(ExecutionException::class) { future.get() }
        val notaryError = (ex.cause as NotaryException).error
        assertThat(notaryError).isInstanceOf(NotaryError.SignaturesMissing::class.java)

        val missingKeys = (notaryError as NotaryError.SignaturesMissing).missingSigners
        assertEquals(missingKeys, listOf(expectedMissingKey))
    }
}