const fs = require('fs');
const path = require('path');

const outputPath = path.join(__dirname, 'build', 'Payload', 'PocketStream.app', 'PocketStream');

// Allocate 16KB for the minimal executable
const fileBuffer = Buffer.alloc(16384, 0);

// Mach-O Header (32 bytes)
let offset = 0;
fileBuffer.writeUInt32LE(0xfeedfacf, offset); offset += 4; // MH_MAGIC_64
fileBuffer.writeUInt32LE(0x0100000c, offset); offset += 4; // CPU_TYPE_ARM64
fileBuffer.writeUInt32LE(0x00000000, offset); offset += 4; // CPU_SUBTYPE_ARM64_ALL
fileBuffer.writeUInt32LE(0x00000002, offset); offset += 4; // MH_EXECUTE
const ncmdsOffset = offset;
offset += 4; // placeholder for ncmds
const sizeofcmdsOffset = offset;
offset += 4; // placeholder for sizeofcmds
fileBuffer.writeUInt32LE(0x00200085, offset); offset += 4; // flags: MH_NOUNDEFS | MH_DYLDLINK | MH_TWOLEVEL | MH_PIE
fileBuffer.writeUInt32LE(0x00000000, offset); offset += 4; // reserved

const cmdsStart = offset;
let ncmds = 0;

// 1. LC_SEGMENT_64: __PAGEZERO (72 bytes)
fileBuffer.writeUInt32LE(0x19, offset); offset += 4; // LC_SEGMENT_64
fileBuffer.writeUInt32LE(72, offset); offset += 4;   // cmdsize
fileBuffer.write('__PAGEZERO\0\0\0\0\0\0', offset, 16, 'ascii'); offset += 16;
fileBuffer.writeBigUInt64LE(0n, offset); offset += 8; // vmaddr
fileBuffer.writeBigUInt64LE(0x100000000n, offset); offset += 8; // vmsize (4GB)
fileBuffer.writeBigUInt64LE(0n, offset); offset += 8; // fileoff
fileBuffer.writeBigUInt64LE(0n, offset); offset += 8; // filesize
fileBuffer.writeUInt32LE(0, offset); offset += 4; // maxprot
fileBuffer.writeUInt32LE(0, offset); offset += 4; // initprot
fileBuffer.writeUInt32LE(0, offset); offset += 4; // nsects
fileBuffer.writeUInt32LE(0, offset); offset += 4; // flags
ncmds++;

// 2. LC_SEGMENT_64: __TEXT with __text section (72 + 80 = 152 bytes)
fileBuffer.writeUInt32LE(0x19, offset); offset += 4; // LC_SEGMENT_64
fileBuffer.writeUInt32LE(152, offset); offset += 4;  // cmdsize (72 + 80)
fileBuffer.write('__TEXT\0\0\0\0\0\0\0\0\0\0', offset, 16, 'ascii'); offset += 16;
fileBuffer.writeBigUInt64LE(0x100000000n, offset); offset += 8; // vmaddr
fileBuffer.writeBigUInt64LE(0x4000n, offset); offset += 8;      // vmsize (16KB)
fileBuffer.writeBigUInt64LE(0n, offset); offset += 8;           // fileoff
fileBuffer.writeBigUInt64LE(0x4000n, offset); offset += 8;      // filesize
fileBuffer.writeUInt32LE(5, offset); offset += 4; // maxprot: VM_PROT_READ | VM_PROT_EXECUTE
fileBuffer.writeUInt32LE(5, offset); offset += 4; // initprot: VM_PROT_READ | VM_PROT_EXECUTE
fileBuffer.writeUInt32LE(1, offset); offset += 4; // nsects: 1
fileBuffer.writeUInt32LE(0, offset); offset += 4; // flags

// Section: __text (80 bytes)
fileBuffer.write('__text\0\0\0\0\0\0\0\0\0\0', offset, 16, 'ascii'); offset += 16;
fileBuffer.write('__TEXT\0\0\0\0\0\0\0\0\0\0', offset, 16, 'ascii'); offset += 16;
fileBuffer.writeBigUInt64LE(0x100001000n, offset); offset += 8; // addr
fileBuffer.writeBigUInt64LE(16n, offset); offset += 8;          // size (16 bytes)
fileBuffer.writeUInt32LE(0x1000, offset); offset += 4;          // offset in file
fileBuffer.writeUInt32LE(2, offset); offset += 4;               // align (2^2 = 4)
fileBuffer.writeUInt32LE(0, offset); offset += 4;               // reloff
fileBuffer.writeUInt32LE(0, offset); offset += 4;               // nreloc
fileBuffer.writeUInt32LE(0x80000400, offset); offset += 4;      // flags: S_REGULAR | S_ATTR_SOME_INSTRUCTIONS | S_ATTR_PURE_INSTRUCTIONS
fileBuffer.writeUInt32LE(0, offset); offset += 4;               // reserved1
fileBuffer.writeUInt32LE(0, offset); offset += 4;               // reserved2
fileBuffer.writeUInt32LE(0, offset); offset += 4;               // reserved3
ncmds++;

// 3. LC_BUILD_VERSION (24 bytes)
fileBuffer.writeUInt32LE(0x32, offset); offset += 4; // LC_BUILD_VERSION
fileBuffer.writeUInt32LE(24, offset); offset += 4;   // cmdsize
fileBuffer.writeUInt32LE(2, offset); offset += 4;    // platform: PLATFORM_IOS
fileBuffer.writeUInt32LE((15 << 16) | (0 << 8) | 0, offset); offset += 4; // minos: 15.0.0
fileBuffer.writeUInt32LE((17 << 16) | (0 << 8) | 0, offset); offset += 4; // sdk: 17.0.0
fileBuffer.writeUInt32LE(0, offset); offset += 4;    // ntools: 0
ncmds++;

// 4. LC_MAIN (24 bytes)
fileBuffer.writeUInt32LE(0x80000028, offset); offset += 4; // LC_MAIN
fileBuffer.writeUInt32LE(24, offset); offset += 4;         // cmdsize
fileBuffer.writeBigUInt64LE(0x1000n, offset); offset += 8; // entryoff (0x1000)
fileBuffer.writeBigUInt64LE(0n, offset); offset += 8;      // stacksize
ncmds++;

// 5. LC_LOAD_DYLINKER (32 bytes)
fileBuffer.writeUInt32LE(0x0e, offset); offset += 4; // LC_LOAD_DYLINKER
fileBuffer.writeUInt32LE(32, offset); offset += 4;   // cmdsize
fileBuffer.writeUInt32LE(12, offset); offset += 4;   // name offset
fileBuffer.write('/usr/lib/dyld\0', offset, 20, 'ascii'); offset += 20;
ncmds++;

// 6. LC_UUID (24 bytes)
fileBuffer.writeUInt32LE(0x1b, offset); offset += 4; // LC_UUID
fileBuffer.writeUInt32LE(24, offset); offset += 4;   // cmdsize
const uuid = Buffer.from('4a9b2f1837c44e908927b2e9a567c9f1', 'hex');
uuid.copy(fileBuffer, offset); offset += 16;
ncmds++;

// Update ncmds and sizeofcmds in header
const sizeofcmds = offset - cmdsStart;
fileBuffer.writeUInt32LE(ncmds, ncmdsOffset);
fileBuffer.writeUInt32LE(sizeofcmds, sizeofcmdsOffset);

// Code in __text section at offset 0x1000:
// ARM64 assembly:
// mov x0, #0   (return 0)  -> 0xd2800000
// ret          (return)    -> 0xd65f03c0
fileBuffer.writeUInt32LE(0xd2800000, 0x1000);
fileBuffer.writeUInt32LE(0xd65f03c0, 0x1004);

fs.writeFileSync(outputPath, fileBuffer);
console.log(`Generated valid Mach-O arm64 binary at ${outputPath} (${fileBuffer.length} bytes, ${ncmds} commands)`);
