package net.corda.core.crypto

import net.corda.core.serialization.OpaqueBytes
import java.security.InvalidKeyException
import java.security.PublicKey
import java.security.SignatureException

/**
 * A wrapper around a digital signature accompanied with metadata, see [MerkleRootWithMeta] and [DigitalSignature.WithKey].
 * The signature protocol works as follows: s = sign(merkleRootWithMeta).
 */
// TODO: change signer's public key with index in the transaction's commands.
class TransactionSignature(bytes: ByteArray, val transactionMeta: TransactionMeta, val by: PublicKey) : OpaqueBytes(bytes) {
    /**
     * Function to verify a [MerkleRootWithMeta] object's signature.
     * Note that [MerkleRootWithMeta] contains merkle root of the transaction and extra metadata, such as DLT's platform version.
     *
     * @param merkleRoot transaction's merkle root, which along with [transactionMeta] will be used to construct the [MerkleRootWithMeta] object to be signed.
     * @throws InvalidKeyException if the key is invalid.
     * @throws SignatureException if this signatureData object is not initialized properly,
     * the passed-in signatureData is improperly encoded or of the wrong type,
     * if this signatureData algorithm is unable to process the input data provided, etc.
     * @throws IllegalArgumentException if the signature scheme is not supported for this private key or if any of the clear or signature data is empty.
     */
    @Throws(InvalidKeyException::class, SignatureException::class)
    fun verify(merkleRoot: SecureHash) = Crypto.doVerify(merkleRoot, this)

}
