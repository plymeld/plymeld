# Install plymeld

plymeld comes as two programs from one download: the **window**, for seeing a model, its plan and its print, and the
**command line**, `plymeld`, for everything the window does and more. Both read the same config. The tutorials use the
command line.

## Download

From the [releases page](https://github.com/plymeld/plymeld/releases), the latest release. Nothing else is needed.

**Windows**

- `plymeld-win-setup.exe` installs the window and the command line, and puts `plymeld` on your `PATH` for terminals
  opened after it.
- Or `plymeld-win-portable.zip`, which installs nothing: unzip it anywhere. Its top `plymeld.exe` opens the window, and
  the command line is `current\cli\plymeld.exe`, not on your `PATH` (run it by that path, or add its folder yourself).

**Linux**

- `plymeld.AppImage` is the window: make it executable (`chmod +x plymeld.AppImage`) and run it.
- `plymeld-linux-x64.tar.gz` is the command line. Unpack it where you keep programs, and put the folder on your `PATH`
  or link the program into one that is:

```
mkdir -p ~/.local/opt ~/.local/bin
tar -xzf plymeld-linux-x64.tar.gz -C ~/.local/opt
ln -s ~/.local/opt/plymeld/plymeld ~/.local/bin/plymeld
```

**macOS** has no build yet.

## Check it

```
plymeld --version
```

prints the version, such as `0.20261109.1`: the date it was released, and a number for that day.

## Keep it up to date

plymeld does not look for new releases unless you ask it to. To have it say so when one is out (at most once a day,
after a command you run in a terminal):

```
plymeld updates --set notify
```

`plymeld update` installs the newest release over the one you have: the Windows installs and the AppImage through their
own updater, the Linux command line by replacing its files where you unpacked it, once the download matches its
published checksum. A post in the [Announcements](https://github.com/plymeld/plymeld/discussions/categories/announcements)
says what each release brings.

## Build it from source instead

With JDK 21 (the Gradle wrapper fetches everything else), from a clone of the repository:

```
./gradlew :plymeld-cli:installDist
plymeld-cli/build/install/plymeld/bin/plymeld --version
```

The launcher needs a Java runtime; `tools/build-native.sh` (`.cmd` on Windows) builds a native program with GraalVM
instead, as the releases are.

## Next

[Your first print](first-slice.md): set up your printer and filament, and slice a model.
