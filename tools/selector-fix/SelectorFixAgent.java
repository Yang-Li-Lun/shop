import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class SelectorFixAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    Module module,
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] bytes) {
                if (!"sun/nio/ch/WEPollSelectorImpl".equals(className)) return null;
                byte[] patched = bytes.clone();
                for (int i = 0; i + 5 < patched.length; i++) {
                    // new PipeImpl(provider, true, false): force the first flag to false,
                    // making PipeImpl use a TCP loopback pipe instead of AF_UNIX.
                    if ((patched[i] & 0xff) == 0x59 && (patched[i + 1] & 0xff) == 0x2b
                            && (patched[i + 2] & 0xff) == 0x04 && (patched[i + 3] & 0xff) == 0x03
                            && (patched[i + 4] & 0xff) == 0xb7) {
                        patched[i + 2] = 0x03;
                        return patched;
                    }
                }
                throw new IllegalStateException("WEPollSelectorImpl bytecode pattern not found");
            }
        });
    }
}
