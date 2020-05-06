package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multibase.binary.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class DirectS3BlockStore implements ContentAddressedStorage {

    private final boolean directWrites, publicReads, authedReads;
    private final Optional<String> baseUrl;
    private final HttpPoster direct;
    private final ContentAddressedStorage fallback;
    private final Multihash nodeId;
    private final LRUCache<PublicKeyHash, Multihash> storageNodeByOwner = new LRUCache<>(100);
    private final CoreNode core;

    public DirectS3BlockStore(BlockStoreProperties blockStoreProperties,
                              HttpPoster direct,
                              ContentAddressedStorage fallback,
                              Multihash nodeId,
                              CoreNode core) {
        this.directWrites = blockStoreProperties.directWrites;
        this.publicReads = blockStoreProperties.publicReads;
        this.authedReads = blockStoreProperties.authedReads;
        this.baseUrl = blockStoreProperties.baseUrl;
        this.direct = direct;
        this.fallback = fallback;
        this.nodeId = nodeId;
        this.core = core;
    }

    public static String hashToKey(Multihash hash) {
        // To be compatible with IPFS we use the same scheme here, the cid bytes encoded as uppercase base32
        String padded = new Base32().encodeAsString(hash.toBytes());
        int padStart = padded.indexOf("=");
        return padStart > 0 ? padded.substring(0, padStart) : padded;
    }

    public static Multihash keyToHash(String keyFileName) {
        // To be compatible with IPFS we use the same scheme here, the cid bytes encoded as uppercase base32
        byte[] decoded = new Base32().decode(keyFileName);
        return Cid.cast(decoded);
    }

    @Override
    public CompletableFuture<Multihash> id() {
        return fallback.id();
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return fallback.startTransaction(owner);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return fallback.closeTransaction(owner, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signatures,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return onOwnersNode(owner).thenCompose(ownersNode -> {
            if (ownersNode && directWrites) {
                CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
                fallback.authWrites(owner, writer, signatures, blocks.stream().map(x -> x.length).collect(Collectors.toList()), false, tid)
                        .thenCompose(preAuthed -> {
                            List<CompletableFuture<Multihash>> futures = new ArrayList<>();
                            for (int i = 0; i < blocks.size(); i++) {
                                PresignedUrl url = preAuthed.get(i);
                                Multihash targetName = keyToHash(url.base.substring(url.base.lastIndexOf("/") + 1));
                                futures.add(direct.put(url.base, blocks.get(i), url.fields)
                                        .thenApply(x -> targetName));
                            }
                            return Futures.combineAllInOrder(futures);
                        }).thenApply(res::complete)
                        .exceptionally(res::completeExceptionally);
                return res;
            }
            return fallback.put(owner, writer, signatures, blocks, tid);
        });
    }

    private CompletableFuture<Boolean> onOwnersNode(PublicKeyHash owner) {
        Multihash cached = storageNodeByOwner.get(owner);
        if (cached != null)
            return Futures.of(cached.equals(nodeId));
        return core.getUsername(owner)
                .thenCompose(user -> core.getChain(user))
                .thenApply(chain -> {
                    List<Multihash> storageProviders = chain.get(chain.size() - 1).claim.storageProviders;
                    Multihash mainNode = storageProviders.get(0);
                    storageNodeByOwner.put(owner, mainNode);
                    System.out.println("Are we on owner's node? " + mainNode + " == " + nodeId);
                    return mainNode.equals(nodeId);
                });
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid) {
        return onOwnersNode(owner).thenCompose(ownersNode -> {
            if (ownersNode && directWrites) {
                CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
                fallback.authWrites(owner, writer, signatures, blocks.stream().map(x -> x.length).collect(Collectors.toList()), true, tid)
                        .thenCompose(preAuthed -> {
                            List<CompletableFuture<Multihash>> futures = new ArrayList<>();
                            for (int i = 0; i < blocks.size(); i++) {
                                PresignedUrl url = preAuthed.get(i);
                                Multihash targetName = keyToHash(url.base.substring(url.base.lastIndexOf("/") + 1));
                                futures.add(direct.put(url.base, blocks.get(i), url.fields)
                                        .thenApply(x -> targetName));
                            }
                            return Futures.combineAllInOrder(futures);
                        }).thenApply(res::complete)
                        .exceptionally(res::completeExceptionally);
                return res;
            }
            return fallback.putRaw(owner, writer, signatures, blocks, tid);
        });
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Multihash> hashes,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        if (publicReads || ! authedReads)
            return NetworkAccess.downloadFragments(hashes, this, monitor, spaceIncreaseFactor);

        // Do a bulk auth in a single call
        List<Pair<Integer, Multihash>> indexAndHash = IntStream.range(0, hashes.size())
                .mapToObj(i -> new Pair<>(i, hashes.get(i)))
                .collect(Collectors.toList());
        List<Pair<Integer, Multihash>> nonIdentity = indexAndHash.stream()
                .filter(p -> ! p.right.isIdentity())
                .collect(Collectors.toList());
        return fallback.authReads(nonIdentity.stream().map(p -> p.right).collect(Collectors.toList()))
                .thenCompose(preAuthedGets ->
                        Futures.combineAllInOrder(IntStream.range(0, preAuthedGets.size())
                                .parallel()
                                .mapToObj(i -> direct.get(preAuthedGets.get(i).base)
                                        .thenApply(b -> {
                                            Pair<Integer, Multihash> hashAndIndex = nonIdentity.get(i);
                                            return new Pair<>(hashAndIndex.left,
                                                    new FragmentWithHash(new Fragment(b), hashAndIndex.right));
                                        }))
                                .collect(Collectors.toList()))
                ).thenApply(retrieved -> {
                    List<FragmentWithHash> res = new ArrayList<>(hashes.size());
                    for (Pair<Integer, FragmentWithHash> p : retrieved) {
                        res.set(p.left, p.right);
                    }
                    for (int i=0; i < res.size(); i++)
                        if (res.get(i) == null) {
                            Multihash identity = hashes.get(i);
                            if (! identity.isIdentity())
                                throw new IllegalStateException("Hash should be identity!");
                            res.set(i, new FragmentWithHash(new Fragment(identity.getHash()), identity));
                        }
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        return getRaw(hash).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
        if (publicReads) {
            CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
            direct.get(baseUrl.get() + hashToKey(hash))
                    .thenApply(Optional::of)
                    .thenAccept(res::complete)
                    .exceptionally(t -> {
                        fallback.authReads(Arrays.asList(hash))
                                .thenCompose(preAuthedGet -> direct.get(preAuthedGet.get(0).base))
                                .thenApply(Optional::of)
                                .thenAccept(res::complete)
                                .exceptionally(e -> {
                                    fallback.getRaw(hash)
                                            .thenAccept(res::complete)
                                            .exceptionally(f -> {
                                                res.completeExceptionally(f);
                                                return null;
                                            });
                                    return null;
                                });
                        return null;
                    });
            return res;
        }
        if (authedReads) {
            CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
            fallback.authReads(Arrays.asList(hash))
                    .thenCompose(preAuthedGet -> direct.get(preAuthedGet.get(0).base))
                    .thenApply(Optional::of)
                    .thenAccept(res::complete)
                    .exceptionally(t -> {
                        fallback.getRaw(hash)
                                .thenAccept(res::complete)
                                .exceptionally(e -> {
                                    res.completeExceptionally(e);
                                    return null;
                                });
                        return null;
                    });
            return res;
        }
        return fallback.getRaw(hash);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return fallback.getSize(block);
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return Futures.of(Collections.singletonList(updated));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash) {
        return Futures.of(Collections.singletonList(hash));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash) {
        return Futures.of(Collections.singletonList(hash));
    }

    @Override
    public CompletableFuture<Boolean> gc() {
        return Futures.errored(new IllegalStateException("S3 doesn't implement GC!"));
    }
}
