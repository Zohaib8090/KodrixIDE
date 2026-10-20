const path = require('path');
const fs = require('fs');

console.log('--- NAPI Loader Verbose Check ---');
const libPath = process.argv[2];

if (!libPath) {
    console.error('Error: No library path provided.');
    process.exit(1);
}

const absolutePath = path.resolve(libPath);
console.log(`Target library: ${absolutePath}`);

if (!fs.existsSync(absolutePath)) {
    console.error(`Error: File does not exist at ${absolutePath}`);
    process.exit(1);
}

console.log('Attempting to require()...');
try {
    // We use a try-catch, but a segfault will bypass this
    const nativeModule = require(absolutePath);
    console.log('Successfully loaded native module!');
    console.log('Module keys:', Object.keys(nativeModule));
} catch (err) {
    console.error('Load failed with error:');
    console.error(err.message);
    if (err.stack) console.error(err.stack);
    process.exit(1);
}
