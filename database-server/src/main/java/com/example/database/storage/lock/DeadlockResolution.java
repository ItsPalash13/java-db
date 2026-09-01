package com.example.database.storage.lock;

/** Who to abort after a wait-for cycle is detected. */
public enum DeadlockResolution {
    ABORT_YOUNGEST,
    ABORT_REQUESTER
}
