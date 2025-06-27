#!/usr/bin/env node

const child_process = require('child_process');
const path = require('path');
const fs = require('fs');

// Debug levels configuration
const DEBUG_LEVELS = {
    'basic': ['--enable-logging', '--v=1', '--log-level=0'],
    'verbose': ['--enable-logging', '--v=2', '--log-level=0', '--enable-logging=stderr'],
    'full': ['--enable-logging', '--v=3', '--log-level=0', '--enable-logging=stderr', '--vmodule=*=3'],
    'gpu': ['--enable-logging', '--v=1', '--log-level=0', '--enable-gpu-debug-layer', '--enable-gpu-service-logging'],
    'network': ['--enable-logging', '--v=1', '--log-level=0', '--log-net-log', '--net-log-capture-mode=Everything']
};

function showHelp() {
    console.log(`
Debug Runner for CircuitJS1 Desktop Mod

Usage: node scripts/debug_runner.js [options] [target]

Options:
  --level <level>     Debug level: basic, verbose, full, gpu, network (default: basic)
  --help             Show this help message

Targets:
  devmode            Run development mode with debug logging
  gwt                Run built GWT application with debug logging
  release            Run compiled release version from out/linux-x64/

Examples:
  node scripts/debug_runner.js devmode
  node scripts/debug_runner.js --level verbose gwt
  node scripts/debug_runner.js --level gpu devmode
  node scripts/debug_runner.js --level full release
`);
}

function parseArgs() {
    const args = process.argv.slice(2);
    const options = {
        level: 'basic',
        target: null
    };

    for (let i = 0; i < args.length; i++) {
        switch (args[i]) {
            case '--help':
                showHelp();
                process.exit(0);
                break;
            case '--level':
                if (i + 1 < args.length) {
                    options.level = args[++i];
                } else {
                    console.error('Error: --level requires a value');
                    process.exit(1);
                }
                break;
            default:
                if (!options.target) {
                    options.target = args[i];
                } else {
                    console.error(`Error: Unknown argument: ${args[i]}`);
                    process.exit(1);
                }
                break;
        }
    }

    return options;
}

function runWithDebug(target, debugArgs) {
    let executablePath;
    let targetPath;

    switch (target) {
        case 'devmode':
            executablePath = require('nw').findpath();
            targetPath = './scripts/devmode';
            break;
        case 'gwt':
            executablePath = require('nw').findpath();
            targetPath = './target/site';
            break;
        case 'release':
            executablePath = './out/linux-x64/CircuitJS1 Desktop Mod/CircuitSimulator';
            targetPath = null;

            // Check if the executable exists
            if (!fs.existsSync(executablePath)) {
                console.error(`Error: Release executable not found at: ${executablePath}`);
                console.error('Please build the release version first using:');
                console.error('  node scripts/dev_n_build.js --buildall');
                process.exit(1);
            }
            break;
        default:
            console.error(`Error: Unknown target: ${target}`);
            console.error('Available targets: devmode, gwt, release');
            process.exit(1);
    }

    console.log(`Starting ${target} with debug level: ${options.level}`);
    console.log(`Debug arguments: ${debugArgs.join(' ')}`);
    console.log('---');

    let spawnArgs;
    let spawnOptions;

    if (target === 'release') {
        // For nw.js_mod release, try different approaches
        console.log('Note: nw.js_mod has limited debug capabilities due to optimization');
        console.log('Attempting to capture output through stdio redirection...');

        // Since nw.js_mod may not support standard Chromium debug flags,
        // we'll focus on capturing any available output
        spawnArgs = []; // No debug args for nw.js_mod
        spawnOptions = {
            cwd: '.',
            stdio: 'inherit',
            env: {
                ...process.env,
                // Try some environment variables that might enable debugging
                'NWJS_DEBUG': '1',
                'DEBUG': '*',
                'NODE_DEBUG': '*'
            }
        };
    } else {
        // For nw.js versions, pass target path first, then debug args
        spawnArgs = [targetPath, ...debugArgs];
        spawnOptions = {
            cwd: '.',
            stdio: 'inherit'
        };
    }

    const child = child_process.spawn(
        executablePath,
        spawnArgs,
        spawnOptions
    );

    child.on('close', (code) => {
        console.log(`\n--- Process exited with code: ${code}`);
    });

    child.on('error', (err) => {
        console.error(`Error starting process: ${err.message}`);
    });
}

// Main execution
const options = parseArgs();

if (!options.target) {
    console.error('Error: No target specified');
    showHelp();
    process.exit(1);
}

if (!DEBUG_LEVELS[options.level]) {
    console.error(`Error: Unknown debug level: ${options.level}`);
    console.error(`Available levels: ${Object.keys(DEBUG_LEVELS).join(', ')}`);
    process.exit(1);
}

const debugArgs = DEBUG_LEVELS[options.level];
runWithDebug(options.target, debugArgs);
