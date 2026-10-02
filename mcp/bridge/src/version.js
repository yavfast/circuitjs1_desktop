// The package version, for `--version` and the stdio server's serverInfo (SP_MCB_02_01).

import fs from 'node:fs';

const pkg = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'));

/** @type {string} */
export const VERSION = pkg.version;
/** @type {string} */
export const NAME = pkg.name;
