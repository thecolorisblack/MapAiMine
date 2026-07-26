#!/usr/bin/env node
/**
 * Offline content validator: run before committing new styles or blueprints.
 *   npm run validate:content [-- --dir path/to/content]
 */
import { loadConfig } from '../config.js';
import { loadContent, validateBlueprint, validateStyle } from '../content/loader.js';
import { REQUIRED_ROLES, textOf } from '../content/types.js';
import { Palette } from '../build/palette.js';
import { placeBlueprint } from '../build/blueprint.js';
import { Rng } from '../util/rng.js';

const argv = process.argv.slice(2);
const dirFlag = argv.indexOf('--dir');
const cfg = loadConfig();
const dirs = dirFlag >= 0 ? [argv[dirFlag + 1]] : [cfg.contentDir, ...cfg.extraContentDirs];

const lib = loadContent(dirs);
const errors: string[] = [];
const warnings: string[] = [...lib.warnings];

console.log(`Content roots: ${lib.dirs.join(', ') || '(none found)'}`);
console.log(`Loaded: ${lib.styles.size} styles, ${lib.blueprints.size} blueprints, ${lib.props.size} props\n`);

for (const style of lib.styles.values()) {
  errors.push(...validateStyle(style, REQUIRED_ROLES));
}

for (const bp of [...lib.blueprints.values(), ...lib.props.values()]) {
  errors.push(...validateBlueprint(bp, lib));
}

// Cross-check: every blueprint must render in every style without falling back to stone.
const sampleStyles = [...lib.styles.values()];
for (const bp of lib.blueprints.values()) {
  for (const style of sampleStyles) {
    try {
      const result = placeBlueprint(bp, {
        origin: [0, 64, 0],
        style,
        props: lib.props,
        seed: 1,
        skipPrepare: true,
      });
      for (const w of result.warnings) warnings.push(`${bp.id} × ${style.id}: ${w}`);
      if (result.blockCount === 0) errors.push(`${bp.id} × ${style.id}: rendered zero blocks`);
    } catch (err) {
      errors.push(`${bp.id} × ${style.id}: render threw — ${(err as Error).message}`);
    }
  }
}

// Report which required roles each style resolves through a fallback rather than directly.
for (const style of lib.styles.values()) {
  const palette = new Palette(style, new Rng(1));
  const missing = REQUIRED_ROLES.filter((r) => !style.materials?.[r]);
  if (missing.length) {
    warnings.push(`style ${style.id}: roles resolved via fallback — ${missing.join(', ')}`);
  }
  for (const role of REQUIRED_ROLES) {
    if (!palette.hasRole(role)) errors.push(`style ${style.id}: role "${role}" unresolvable even via fallback`);
  }
}

const uniqueWarnings = [...new Set(warnings)];
if (uniqueWarnings.length) {
  console.log(`⚠ ${uniqueWarnings.length} warnings:`);
  for (const w of uniqueWarnings.slice(0, 60)) console.log(`  - ${w}`);
  if (uniqueWarnings.length > 60) console.log(`  … and ${uniqueWarnings.length - 60} more`);
  console.log('');
}

if (errors.length) {
  const unique = [...new Set(errors)];
  console.error(`✘ ${unique.length} errors:`);
  for (const e of unique.slice(0, 100)) console.error(`  - ${e}`);
  process.exit(1);
}

console.log('✔ content is valid');
for (const style of lib.styles.values()) {
  console.log(`  ${style.id.padEnd(16)} ${textOf(style.name, 'ru')}`);
}
