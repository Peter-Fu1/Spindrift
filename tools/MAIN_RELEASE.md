# Local Main release tools

Use the existing protected Main key and the matching certificate lock. The build and alignment receipt must identify the exact Git revision. See release_main.py --help for inputs. A candidate keeps ready=false and leaves the release ledger intact.

The backup tool uses a local interactive terminal with hidden password input. Keep encrypted portable copies on independent media, including one off-host copy, and verify restoration before release. CI runs with unsigned builds or disposable laboratory keys.
