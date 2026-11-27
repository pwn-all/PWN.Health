# Models that ship

The networks in `app/src/main/assets/`, by their bytes and SHA-256. `DocsMapTest` holds every model
in the assets, and the OCR models' dictionaries, to a row here: a network swapped in the assets
without a new row fails the build. Their licences, and what the code's GPL does not cover, are in
the README's Licence section.

## Shipped in 2.0

| File | What it is | Bytes | SHA-256 |
|---|---|---:|---|
| `chat/reader.onnx` | The chat's message reader (`chat-reader 1.0`, int8 weights); its package is `chat/reader-manifest.json` beside it, and what it learned from is in `chat/NOTICES.md` | 66,416,948 | `890bc171adf07c68cfe66a40c60a3ac6df566935ef3076cd3d90bcdf8b3ba14f` |
| `photo_v3.onnx` | One PE-Core-S16 network for dishes and products | 28,289,967 | `63c539a1c09801e4453f9ff74ee6c0b6037a22ebd6c22339b713d0fe6cf03188` |
| `ocr/PP-OCRv6_small_det.onnx` | PaddleOCR's PP-OCRv6 small text detector (Apache-2.0) | 9,880,512 | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` |
| `ocr/PP-OCRv6_small_rec.onnx` | PP-OCRv6 small recognizer: Latin, Chinese and Japanese in one model | 21,159,378 | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` |
| `ocr/PP-OCRv6_small_rec.dict.txt` | The letters the PP-OCRv6 recognizer reads out, in its own order | 74,947 | `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d` |
| `ocr/eslav_PP-OCRv5_mobile_rec.onnx` | PP-OCRv5's East Slavic recognizer, for Russian and Ukrainian print, which no v6 recognizer has a letter of | 7,910,143 | `ab2bed57958024dcd55b8889a73e0bbe7144178d71a0706c4991d0f0b853580e` |
| `ocr/eslav_PP-OCRv5_mobile_rec.dict.txt` | The letters the East Slavic recognizer reads out | 1,662 | `775a31c35bcd0713eabb0a6a68f1e272546c5ab27817b7888df764cd9a549008` |
