package com.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

import com.app.modules.comment.service.impl.CommentMentionServiceImpl;
import com.app.modules.comment.service.impl.CommentServiceImpl;
import com.app.modules.message.service.impl.ConversationServiceImpl;
import com.app.modules.message.service.impl.MessageServiceImpl;
import com.app.modules.post.service.impl.PostCollaborationServiceImpl;
import com.app.modules.post.service.impl.PostMentionServiceImpl;
import com.app.modules.post.service.impl.PostTagServiceImpl;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.service.SocialService;

/**
 * Pins the rule that a comment, a conversation start, a message send, a mention, a people tag and a
 * collaboration invitation reach the block check through {@link InteractionPolicyService} and never
 * through a direct {@code SocialService.isBlockedBetween} call.
 *
 * <p>A direct call would answer "blocked or not" without the limit, message permission, pending
 * request and hidden word rules that decide the rest, and a later change could add such a call
 * without anyone noticing that a rule had been skipped. The bytecode of each service is scanned, so
 * a private helper or a lambda inside the service is covered as well as its public methods.
 */
class PolicyCallSiteArchitectureTest {

    private static final List<Class<?>> POLICY_GOVERNED_SERVICES =
            List.of(
                    CommentServiceImpl.class,
                    CommentMentionServiceImpl.class,
                    ConversationServiceImpl.class,
                    MessageServiceImpl.class,
                    PostCollaborationServiceImpl.class,
                    PostMentionServiceImpl.class,
                    PostTagServiceImpl.class);

    private static final String SOCIAL_SERVICE = internalName(SocialService.class);

    @Test
    void policyGovernedServices_dependOnInteractionPolicyService() {
        for (Class<?> service : POLICY_GOVERNED_SERVICES) {
            List<Class<?>> fieldTypes = new ArrayList<>();
            for (Field field : service.getDeclaredFields()) {
                fieldTypes.add(field.getType());
            }

            assertThat(fieldTypes)
                    .as("%s must hold the interaction policy", service.getSimpleName())
                    .contains(InteractionPolicyService.class);
        }
    }

    @Test
    void policyGovernedServices_neverCallIsBlockedBetweenDirectly() throws IOException {
        for (Class<?> service : POLICY_GOVERNED_SERVICES) {
            assertThat(callsIsBlockedBetween(service))
                    .as(
                            "%s must ask the interaction policy instead of calling"
                                    + " SocialService.isBlockedBetween",
                            service.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    void scanner_findsADirectIsBlockedBetweenCall() throws IOException {
        assertThat(callsIsBlockedBetween(DirectBlockCheck.class))
                .containsExactly("blockedPair", "viaLambda");
    }

    private static List<String> callsIsBlockedBetween(Class<?> type) throws IOException {
        List<String> callers = new ArrayList<>();
        try (InputStream bytecode = type.getResourceAsStream("/" + internalName(type) + ".class")) {
            assertThat(bytecode).as("bytecode of %s", type.getName()).isNotNull();
            new ClassReader(bytecode)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                int opcode,
                                                String owner,
                                                String calledName,
                                                String calledDescriptor,
                                                boolean isInterface) {
                                            if (SOCIAL_SERVICE.equals(owner)
                                                    && "isBlockedBetween".equals(calledName)) {
                                                callers.add(lambdaOwner(name));
                                            }
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return callers.stream().distinct().sorted().toList();
    }

    // A lambda body compiles to a synthetic lambda$<method>$<n>; report the method that wrote it
    private static String lambdaOwner(String methodName) {
        if (!methodName.startsWith("lambda$")) {
            return methodName;
        }
        String withoutPrefix = methodName.substring("lambda$".length());
        return withoutPrefix.substring(0, withoutPrefix.lastIndexOf('$'));
    }

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** Fixture with the call shape the scanner must catch, in a method and in a lambda. */
    @SuppressWarnings("unused")
    private static final class DirectBlockCheck {

        private final SocialService socialService = null;

        boolean blockedPair(java.util.UUID a, java.util.UUID b) {
            return socialService.isBlockedBetween(a, b);
        }

        java.util.function.BooleanSupplier viaLambda(java.util.UUID a, java.util.UUID b) {
            return () -> socialService.isBlockedBetween(a, b);
        }

        boolean harmless(java.util.UUID a) {
            return a != null;
        }
    }
}
