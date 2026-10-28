# Third-party notices

The X32 / M32 / WING Multitrack Tool bundles the following software.

## FlatLaf

- Project: https://www.formdev.com/flatlaf/ ([source](https://github.com/JFormDesigner/FlatLaf))
- Version: 3.7.2
- Copyright 2019 FormDev Software GmbH
- License: Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0)

FlatLaf provides the application's look and feel. Its license text is also included inside the
application jar as `META-INF/LICENSE-FlatLaf.txt`.

## Java runtime (installer and package builds only)

The packaged builds include a Java runtime created with `jpackage`/`jlink`:

- **Windows** installer and portable zip: from Oracle JDK 26, licensed under the Oracle No-Fee
  Terms and Conditions (https://www.oracle.com/downloads/licenses/no-fee-license.html).
- **Linux** `.deb`, `.rpm` and `.tar.gz`: from OpenJDK 21 as packaged by Ubuntu, licensed under
  the GNU General Public License, version 2, with the Classpath Exception
  (https://openjdk.org/legal/gplv2+ce.html). The runtime's own legal notices are in
  `lib/runtime/legal/`.

The plain `.jar` contains no Java runtime.

## Acknowledgements

The X-Live `SE_LOG.BIN` layout was learned from Patrick-Gilles Maillot's open-source X32 tools
(https://github.com/pmaillot/X32-Behringer).
