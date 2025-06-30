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

- **Java** (core simulation engine and desktop application)
- **JavaScript/HTML/CSS** (web interface, GWT-based frontend)
- **Maven** (build automation)
- **Node.js** (scripts for development and packaging)
- **Inno Setup** (Windows installer creation)

## Project Structure

- `src/main/java/` — Java source code for the simulation engine and desktop application.
- `site/` — Web assets (HTML, JS, CSS) for the frontend and documentation.
- `war/` — Web application resources for deployment.
- `docs/` — Project documentation (this file, guides, etc.).
- `scripts/` — Development and build scripts (Node.js, shell scripts).
- `icons/` — Application icons for various platforms.
- `templates/` — Localization templates for different languages.
- `tests/` — Test circuit files for simulation validation.
- `target/` — Maven build output (JAR, WAR, compiled classes).

## Getting Started

### Prerequisites

- **Java JDK 8+**
- **Node.js** (for development scripts)
- **Maven** (for building Java components)
- (Optional) **Inno Setup** (for building Windows installers)

### Build and Run

1. **Build the project:**
   ```sh
   mvn clean package
   ```
   This will generate the necessary JAR/WAR files in the `target/` directory.

2. **Run the desktop application:**
   - Use the generated JAR file:
     ```sh
     java -jar target/circuitjs1mod-<version>.jar
     ```
   - Or use provided scripts in the `scripts/` directory for development/debugging.

3. **Run in development mode:**
   - Use Node.js scripts or shell scripts in `scripts/` (e.g., `dev_n_build.js`, `quick_debug.sh`).

### Parameters

- The application accepts standard Java command-line parameters (e.g., `-Xmx` for memory).
- Additional parameters may be available for debugging or custom configuration (see scripts and README for details).

## Debugging Methods

- **Debug scripts:** Use `scripts/debug_runner.js` or `scripts/debug-logger.js` for enhanced logging and debugging.
- **Shell scripts:** `scripts/quick_debug.sh` and `scripts/run_release_debug.sh` provide quick ways to launch the app in debug mode.
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

See `LICENSE` for open-source license details. The project is distributed under the MIT License.

### Contributing

Contributions are welcome. Please follow the existing code style and submit pull requests with clear descriptions. Refer to the `README.md` for guidelines.

### References

- [CircuitJS1 Original Project](https://github.com/sharpie7/circuitjs1)
- [GWT (Google Web Toolkit)](http://www.gwtproject.org/)
- [Inno Setup](http://www.jrsoftware.org/isinfo.php)

---

*For further details, refer to the README.md and comments in the source code.*
