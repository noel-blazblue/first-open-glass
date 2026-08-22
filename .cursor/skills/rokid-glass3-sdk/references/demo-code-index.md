# Demo Code Index

The official Demo source is provided in the current package:

glass3_sdk_demo.zip

Use the Demo first to validate the connection between phone and glasses, then map the target capability to the corresponding code sample document.

## Main Documents

- Demo running guide: `references/docs/downloads/demo-guide.md`
- Code sample overview: `references/docs/downloads/samples.md`
- Device connection samples: `references/docs/代码示例/10-device-connection/`
- Messaging and file transfer samples: `references/docs/代码示例/20-message-transfer/`
- Media samples: `references/docs/代码示例/30-media/`
- Voice, TTS and AI samples: `references/docs/代码示例/35-voice-ai/`
- Face and license plate recognition samples: `references/docs/代码示例/40-vision/`
- OTA and system setting samples: `references/docs/代码示例/50-system/`

## Extract Demo Package

Run `scripts/extract-demo-package.sh /path/to/glass3_sdk_demo.zip` from this skill package to extract the Demo source locally.

## API Naming Notes

- For phone-to-glasses file sending, prefer the documented operators such as `getFileOperater().sendFile(...)`, `getBtFileOperater().sendFile(...)`, or `getApkFileOperator()?.sendFile(...)`. Do not invent generic methods such as `sdk.sendFile(...)`.
