package eu.kanade.tachiyomi.util.qnn

import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes everything that brings the QNN/HTP runtime up or down in this process.
 *
 * Two independent runtimes exist: the inference backend in `qnn_backend` and the offline model
 * compiler in `spatial_qnn`. Both dlopen the same vendor libraries and create an HTP device, and
 * the driver does not tolerate one being torn down while the other initializes. Backend switches
 * (which free the HTP context) and model preparation therefore take this lock.
 *
 * The lock is reentrant: preparing a model from inside the decoding path (`initializeQnnIfAvailable`)
 * already holds it, and the nested call must not block.
 */
object NpuRuntimeLock {
    val lock = ReentrantLock()
}
