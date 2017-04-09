package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class MkdirSpeed {

    public static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public MkdirSpeed(String useIPFS, Random r) throws Exception {
        int portMin = 9000;
        int portRange = 2000;
        int webPort = portMin + r.nextInt(portRange);
        int corePort = portMin + portRange + r.nextInt(portRange);
        Args args = Args.parse(new String[]{"useIPFS", ""+useIPFS.equals("IPFS"), "-port", Integer.toString(webPort), "-corenodePort", Integer.toString(corePort)});
        Start.local(args);
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();
        // use insecure random otherwise tests take ages
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {"IPFS", new Random(0)}
        });
    }

    static void setFinalStatic(Field field, Object newValue) throws Exception {
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.set(null, newValue);
    }

    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return UserContext.ensureSignedUp(username, password, network, crypto).get();
    }

    @Test
    public void hugeFolder() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();
        List<String> names = new ArrayList<>();
        IntStream.range(0, 1000).forEach(i -> names.add(randomString()));

        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();
        for (int i=0; i < names.size(); i++) {
            String filename = names.get(i);
            long t1 = System.currentTimeMillis();
            userRoot.mkdir(filename, context, false, context.crypto.random).get();
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("MKDIR(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i, duration, best, worst, (t1 + duration - start) / (i + 1));
        }
    }

    private static String randomString() {
        return UUID.randomUUID().toString();
    }
}
