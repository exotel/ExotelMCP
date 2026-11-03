#!/usr/bin/env node
// Copies ../skills (repo root, also consumable by `npx skills add exotel/ExotelMCP`) into the npm package.
const fs = require('fs');
const path = require('path');

const src = path.resolve(__dirname, '..', '..', 'skills');
const dest = path.resolve(__dirname, '..', 'skills');
if (!fs.existsSync(src)) {
  console.error(`bundle-skills: ${src} not found`);
  process.exit(1);
}
fs.rmSync(dest, { recursive: true, force: true });
fs.cpSync(src, dest, { recursive: true });
console.log(`bundle-skills: copied ${fs.readdirSync(dest).length} skills into ${dest}`);
