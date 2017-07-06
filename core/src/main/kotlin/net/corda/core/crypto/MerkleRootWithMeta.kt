package net.corda.core.crypto

import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.serialize

/**
 * Using a [MerkleRootWithMeta] object a signer can add extra information on the transaction signature.
 * It actually works as a wrapper that contains the Merkle root along with extra transactionMetaData, such as DLT's platformVersion.
 *
 * @param merkleRoot the merkle root of the transaction.
 * @param transactionMeta other meta data required, such as platform version and signer's public key.
 */
@CordaSerializable
class MerkleRootWithMeta(val merkleRoot: SecureHash,
                         val transactionMeta: TransactionMeta) {

    fun bytes() = this.serialize().bytes

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MerkleRootWithMeta) return false
        return merkleRoot == other.merkleRoot && transactionMeta == transactionMeta
    }

    override fun hashCode(): Int {
        var result = merkleRoot.hashCode()
        result = 31 * result + transactionMeta.hashCode()
        return result
    }

    override fun toString(): String {
        return "MerkleRootWithMeta(merkleRoot=$merkleRoot, transactionMeta=$transactionMeta)"
    }

    fun transactionMetaBytes() = transactionMeta.bytes()
}

