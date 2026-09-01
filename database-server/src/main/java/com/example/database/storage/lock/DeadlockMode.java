package com.example.database.storage.lock;

/** How the lock manager handles would-be deadlock cycles. */
public enum DeadlockMode {
  /** Wait-Die or Wound-Wait — decide before parking. */
  PREVENT,
  /** Wait-for graph + victim policy after a cycle is found. */
  DETECT_RESOLVE
}
