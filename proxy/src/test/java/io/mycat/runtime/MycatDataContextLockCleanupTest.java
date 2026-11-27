package io.mycat.runtime;

import io.mycat.beans.mycat.TransactionType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MycatDataContextLockCleanupTest {

    @Test
    void closeAllLockDoesNotRemoveLockRegisteredAfterSnapshot() throws Exception {
        Context context = new Context();
        SnapshotInjectingSet locks = new SnapshotInjectingSet();
        Field field = MycatDataContextImpl.class.getDeclaredField("usedlocks");
        field.setAccessible(true);
        field.set(context, locks);

        context.closeAllLock();

        assertTrue(locks.contains("registered-after-snapshot"));
    }

    private static final class Context extends MycatDataContextImpl {
        @Override
        public void switchTransaction(TransactionType transactionType) {
        }
    }

    private static final class SnapshotInjectingSet extends HashSet<String> {
        @Override
        public Iterator<String> iterator() {
            Iterator<String> snapshot = super.iterator();
            return new Iterator<>() {
                private boolean injected;

                @Override
                public boolean hasNext() {
                    boolean hasNext = snapshot.hasNext();
                    if (!hasNext && !injected) {
                        add("registered-after-snapshot");
                        injected = true;
                    }
                    return hasNext;
                }

                @Override
                public String next() {
                    return snapshot.next();
                }
            };
        }
    }
}
