package com.fossil.crypto;

public class EllipticCurveKeyPair$CppProxy {
    private long nativeRef;

    public EllipticCurveKeyPair$CppProxy(long nativeRef) {
        this.nativeRef = nativeRef;
    }

    static {
        System.loadLibrary("EllipticCurveCrypto");
    }

    public byte[] getPrivateKey(){
        return this.native_privateKey(this.nativeRef);
    }

    public byte[] getPublicKey(){
        return this.native_publicKey(this.nativeRef);
    }

    public byte[] calculateSecretKey(byte[] otherPublic){
        return this.native_calculateSecretKey(this.nativeRef, otherPublic);
    }

    public native static EllipticCurveKeyPair$CppProxy create();
    private native byte[] native_privateKey(long ref);
    private native byte[] native_publicKey(long ref);
    private native byte[] native_calculateSecretKey(long ref, byte[] otherPublic);
}
