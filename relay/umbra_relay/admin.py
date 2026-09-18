"""Host-only administration. No remote admin API or master decryption key."""
import argparse
import os
from .app import Database


def main():
    parser = argparse.ArgumentParser(description="UMBRA relay administration")
    parser.add_argument("command", choices=["invite", "purge"])
    parser.add_argument("--ttl", type=int, default=3600, help="Invitation lifetime in seconds")
    args = parser.parse_args()
    db = Database(os.environ.get("UMBRA_DB", "/data/umbra.sqlite3"))
    if args.command == "invite":
        print(db.issue_invite(args.ttl))
    else:
        import time
        with db.connect(write=True) as conn:
            db.purge(conn, int(time.time()))
        print("Expired entries removed")


if __name__ == "__main__":
    main()
