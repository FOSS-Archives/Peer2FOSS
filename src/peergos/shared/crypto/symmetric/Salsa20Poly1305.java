package peergos.shared.crypto.symmetric;

import peergos.shared.crypto.*;
import peergos.shared.crypto.random.JSNaCl;

import java.util.concurrent.CompletableFuture;

public interface Salsa20Poly1305 {

    byte[] secretbox(byte[] data, byte[] nonce, byte[] key);

    byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key);

    CompletableFuture<byte[]> secretboxAsync(byte[] data, byte[] nonce, byte[] key);

    CompletableFuture<byte[]> secretbox_openAsync(byte[] cipher, byte[] nonce, byte[] key);

    class Javascript implements Salsa20Poly1305 {
        JSNaCl scriptJS = new JSNaCl();

        @Override
        public byte[] secretbox(byte[] data, byte[] nonce, byte[] key) {
            return scriptJS.secretbox(data, nonce, key);
        }

        @Override
        public byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key) {
            return scriptJS.secretbox_open(cipher, nonce, key);
        }

        @Override
        public CompletableFuture<byte[]> secretboxAsync(byte[] data, byte[] nonce, byte[] key) {
            return scriptJS.secretboxAsync(data, nonce, key);
        }

        @Override
        public CompletableFuture<byte[]> secretbox_openAsync(byte[] cipher, byte[] nonce, byte[] key) {
            return scriptJS.secretbox_openAsync(cipher, nonce, key);
        }
    }

}
