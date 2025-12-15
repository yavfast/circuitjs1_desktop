# CircuitJS1 Desktop Project Documentation

## Overview

**CircuitJS1 Desktop** is a desktop adaptation of the popular CircuitJS1 electronic circuit simulator. It allows users to design, simulate, and analyze electronic circuits in an interactive graphical environment. The project is based on the open-source CircuitJS1 web simulator, providing additional desktop-specific features and packaging for standalone use.

### Key Features

- **Interactive Circuit Design:** Drag-and-drop interface for creating and editing circuits.
- **Real-Time Simulation:** Simulate circuit behavior with real-time updates.
- **Cross-Platform Compatibility:** Runs on Windows, macOS, and Linux.
- **Extensive Component Library:** Includes resistors, capacitors, transistors, diodes, and more.
- **Customizable:** Supports custom components and localization.
- **Offline Mode:** Fully functional without internet access.

## Technology Stack

- **Java** (core simulator + GWT sources compiled to JavaScript)
- **JavaScript/HTML/CSS** (web interface, GWT-based frontend)
- **Maven** (build automation)
- **Node.js** (scripts for development and packaging)
- **Inno Setup** (Windows installer creation)

## Project Structure

- `src/main/java/` — Java source code for the simulation engine and desktop application.
- `war/` — Web application resources for deployment.
- `docs/` — Project documentation (this file, guides, etc.).
- `scripts/` — Development and build scripts (Node.js, shell scripts).
- `icons/` — Application icons for various platforms.
- `templates/` — Localization templates for different languages.
- `tests/` — Test circuit files for simulation validation.
- `target/` — Maven/GWT build output (compiled classes, GWT output, `target/site/`).

## Getting Started

### Prerequisites

- **Java JDK 17+**
- **Node.js** (for development scripts)
- **Maven** (for building Java components)
- (Optional) **Inno Setup** (for building Windows installers)

### Build and Run

1. **Install Node dependencies:**
   ```sh
   npm install
   ```

2. **Build and run (recommended workflow):**
   - Build only the GWT web app:
     ```sh
     npm run buildgwt
     ```
     Output: `./target/site/`
   - Run it in NW.js SDK:
     ```sh
     npm start
     ```

3. **Full desktop build (packaging):**
   - Build a desktop release (currently packages Linux x64 by default):
     ```sh
     npm run build
     ```
   - Full rebuild (complete cleanup, then package Linux x64):
     ```sh
     npm run full
     ```
   Output: `./out/`

4. **GWT DevMode (for UI/Java iteration):**
   ```sh
   npm run devmode
   ```
   DevMode runs directly from `war/` and is separate from `target/site/`.

### Parameters

- Runtime/debug options are controlled via scripts in `scripts/` and build flags; see the root README for the supported `npm` commands.

## Debugging Methods

- **Debug scripts:** Use `scripts/debug_runner.js` or `scripts/debug-logger.js` for enhanced logging and debugging.
- **Shell scripts:** `scripts/run_dev_web.sh` and `scripts/run_dev_app.sh` are available for quick local workflows.
- **Logs:** Check `debug.log` for runtime logs and errors.
- **Maven:** Use `mvnDebug` for remote debugging with IDEs.

## Additional Information

### Localization

The `templates/` directory contains language templates for translation. To add a new language, create a new template file and update the `update_languages.sh` script.

### Installer Creation

Use the `Inno Setup/` scripts to build Windows installers. Customize the `.iss` files for specific configurations.

### Web Version

The `site/` and `war/` directories contain resources for the web version of CircuitJS1. These can be deployed to a web server for online use.

### Licensing

See `LICENSE` for licensing details. This project is distributed under the GNU General Public License (GPL), version 2 or later.

### Contributing

Contributions are welcome. Please follow the existing code style and submit pull requests with clear descriptions. Refer to the `README.md` for guidelines.

### References

- [CircuitJS1 Original Project](https://github.com/sharpie7/circuitjs1)
- [GWT (Google Web Toolkit)](http://www.gwtproject.org/)
- [Inno Setup](http://www.jrsoftware.org/isinfo.php)

---

*For further details, refer to the README.md and comments in the source code.*
