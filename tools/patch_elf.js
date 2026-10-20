const fs = require('fs');
const path = require('path');

const targetFile = process.argv[2];
if (!targetFile) {
    console.error('Usage: node patch_elf.js <file>');
    process.exit(1);
}

console.log(`Patching ${targetFile}...`);
const buffer = fs.readFileSync(targetFile);
let found = false;

const searchStr = '/data/data/com.termux/files/usr/lib';
const replacementBase = '$ORIGIN';

// Calculate padding to match exact length
const paddingLength = searchStr.length - replacementBase.length;
let replacementStr = replacementBase;
if (paddingLength > 0) {
    // Each "/." is 2 chars. 
    const dots = Math.floor(paddingLength / 2);
    for (let i = 0; i < dots; i++) {
        replacementStr += '/.';
    }
    // If we have 1 char left, add a trailing slash or dot
    if (replacementStr.length < searchStr.length) {
        replacementStr += '/';
    }
}

console.log(`Searching for: "${searchStr}"`);
console.log(`Replacing with: "${replacementStr}" (Length: ${replacementStr.length})`);

if (replacementStr.length !== searchStr.length) {
    console.error('Length mismatch! Logic error.');
    process.exit(1);
}

let index = buffer.indexOf(searchStr);
while (index !== -1) {
    console.log(`Found occurrence at ${index}`);
    buffer.write(replacementStr, index);
    found = true;
    index = buffer.indexOf(searchStr, index + 1);
}

if (found) {
    fs.writeFileSync(targetFile, buffer);
    console.log(`Successfully patched ${targetFile}!`);
} else {
    console.log(`No occurrences of "${searchStr}" found in ${targetFile}.`);
}
