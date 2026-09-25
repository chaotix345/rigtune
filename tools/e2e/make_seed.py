"""A seed for the self-update harness from a real instance (plan review H-M2): templated copies of its RigTune files,
with the instance folder replaced by ${INSTANCE} everywhere, and source.json with each original's sha256. Reads only:
nothing under the source folder is written, renamed or opened for writing.

    python tools/e2e/make_seed.py --config "<instance>/config/rigtune" --instance "<instance>"
        --location "%APPDATA%/ModrinthApp/profiles/Fabric 26.2/config/rigtune" --dest tools/e2e/seeds/v010-dh

The seed folder also needs a hand-written seed.json (the fake jars to create); see tools/e2e/README.md."""

import argparse
import datetime
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import fixtures  # noqa: E402

NAMES = ("pending.json", "last-apply.json")


def make_seed(config_dir, instance_root, dest, location, names=NAMES):
    config_dir, dest = Path(config_dir), Path(dest)
    for source in (config_dir, Path(instance_root)):
        if dest.resolve() == source.resolve() or source.resolve() in dest.resolve().parents:
            raise SystemExit("refusing to write the seed into {}: the source instance is read only".format(source))
    dest.mkdir(parents=True, exist_ok=True)
    source = {"location": location, "readAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
              "token": fixtures.TOKEN, "files": {}}
    for name in names:
        data = (config_dir / name).read_bytes()
        (dest / name).write_bytes(fixtures.template_seed_json(data.decode("utf-8"), instance_root).encode("utf-8"))
        source["files"][name] = {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
    (dest / "source.json").write_bytes((json.dumps(source, indent=1) + "\n").encode("utf-8"))
    return source


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", required=True, help="the real instance's config/rigtune folder (read only)")
    parser.add_argument("--instance", required=True, help="the real instance's folder, replaced by ${INSTANCE}")
    parser.add_argument("--location", required=True, help="where the files came from, as recorded in source.json")
    parser.add_argument("--dest", required=True)
    args = parser.parse_args(argv)
    source = make_seed(args.config, args.instance, args.dest, args.location)
    print(json.dumps(source, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
