# Fix: apply helper retries too briefly on a locked jar

Date: 2026-09-25.

## Evidence

Real-world feedback from the user's 0.1.0 apply: after the game exited, the helper renamed 15 files fine but
disabling the 27 MB Distant Horizons jar failed:

```
Gave up after 10 attempt(s): java.nio.file.FileSystemException: ...\mods\fabric-26.2.jar -> ...\mods\fabric-26.2.jar.disabled: The process cannot access the file because it is being used by another process
```

The helper waited for the game pid to exit, then tried within ~3 s (10 × 300 ms) — likely losing a race with the
Modrinth App re-scanning the instance or an AV scanner briefly holding the jar open. The group correctly stayed
pending for the next exit, but the update didn't apply when it should have.

## Change

- `ApplyExecutor`: a sharing violation (any `FileSystemException` other than `NoSuchFileException`/
  `FileAlreadyExistsException`/`DirectoryNotEmptyException` — covers `AccessDeniedException` and the plain,
  locale-specific "being used by another process" case alike) now retries with exponential backoff (300 ms,
  doubling, capped at 5 s per wait) up to a ~30 s total budget, instead of the fast fixed-delay policy. An ordinary
  `IOException` keeps the old fast policy (`DEFAULT_ATTEMPTS` × `DEFAULT_RETRY_DELAY_MILLIS`). Rollback (undoing an
  earlier op in the group after a later one fails) uses the same policy. The sleeper is injectable (`Sleeper`) so
  tests don't sleep for real.
- `ApplyHelper`: the settle delay after the game process exits and before the apply lock is taken moved from 1 s to
  2 s, and is now injectable (`Sleeper`) the same way, so `ApplyHelperTest` can assert it without a real wait.
