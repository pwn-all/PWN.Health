# Notices — the chat reader

What the licences of the parts of this reader ask for: who holds the copyright, the licence and its text, the attribution, the changes made and the snapshot read.

The journal tower. The reader is the message heads described below with a journal tower (package `journal-v2c`; `reader-manifest.json` beside this file names both). The tower's inputs are the generator's own rows only, labelled by the served primary model in the table below, and no row of a public source; so the table adds no source for the tower.

| Component | Use | Licence | Licence text | Copyright | Attribution | Source | Changes |
|---|---|---|---|---|---|---|---|
| the reader's weights (reader.onnx) | shipped | MIT | https://raw.githubusercontent.com/microsoft/unilm/0e31c7c09737df491e7ff74ded19614b884c52b4/LICENSE | Copyright (c) Microsoft Corporation | https://huggingface.co/microsoft/Multilingual-MiniLM-L12-H384 | microsoft/Multilingual-MiniLM-L12-H384@6e8c1ec6b4ec4e3fc6eb7d2cd834fcd582b61daf | fine-tuned with the reader's heads, its embedding table cut to the vocabulary below, exported to ONNX and quantised to int8 |
| the tokenizer's pieces (reader-tokenizer.json) | shipped | MIT | https://raw.githubusercontent.com/facebookresearch/fairseq/e75cff5f2c1d62f12dc911e0bf420025eb1a4e33/LICENSE | Copyright (c) Facebook, Inc. and its affiliates. | XLM-R's SentencePiece model as microsoft/Multilingual-MiniLM-L12-H384 ships it | sentencepiece.bpe.model SHA-256 cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865 | 250002 pieces pruned to 70007, counted on the corpora of the vocabulary choice; 861 emoji added |
| the emoji pieces added to the vocabulary | shipped | Unicode License v3 | https://www.unicode.org/license.txt | Copyright © 1991-2026 Unicode, Inc. | emoji-test.txt (Unicode Technical Standard #51) | https://unicode.org/Public/18.0.0/emoji/emoji-test.txt SHA-256 8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a | 861 single-code-point emoji it lists that XLM-R's pieces lacked, added as pieces |
| the generator's rows (generated-filtered, generated-judged) | training data | the outputs of Apache-2.0 models; seeds Wikidata CC0 | — | — | Wikidata (CC0) seed words | — | — |
| aya | training data | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 | — | Aya Dataset, Cohere For AI (Singh et al., 2024) | data/train-00000-of-00001.parquet SHA-256 51baa85043b569ba117f41516b32d1b0e4e2647fb203a08933f98a556fb1fb06 | the prompts (inputs) only; marked out of scope; lines naming a health word dropped (and what every public source goes through, below) |
| brighter | training data | CC BY 4.0 | https://creativecommons.org/licenses/by/4.0/legalcode | — | BRIGHTER (Muhammad et al., 2025) | arq/dev-00000-of-00001.parquet SHA-256 6177c4ee58222b4009acee6fde38bb3bddc9acc555075a62c59b72db073a2ac0; arq/test-00000-of-00001.parquet SHA-256 1abc1894e1f5dce1a52a8325b3a4c9441c6bf07ec3fdabfc3ca373feb04ee405; arq/train-00000-of-00001.parquet SHA-256 68abb544e6984f092aaa5603703c249092129cddb51beff816220bc90d631bd5; ary/dev-00000-of-00001.parquet SHA-256 ce9681ef8dae4f844b7c61c0972060ae6fbf74cc6841446cc3eb4fe96657dd13; ary/test-00000-of-00001.parquet SHA-256 bd3bc2fcd79ba6701af7a477566c85b767cff187b2c5f01a2775fda2e3f671c8; ary/train-00000-of-00001.parquet SHA-256 39e70cdeb7e74c814614e1aa77e9c1e27fad8c19899c1827a3a60aa80f13aa6e; chn/dev-00000-of-00001.parquet SHA-256 4b9f04f50856f0c5ebc82dccf8f3da0eb9a4ec976a2ca99df29310b7f8c74cfe; chn/test-00000-of-00001.parquet SHA-256 f2527e0692689570f290240f2945482b3f6214e409be0f2635077abde8484703; chn/train-00000-of-00001.parquet SHA-256 c28d86b3502ebee3fcf3eda7c45f105faa7e7fa86d583ae20a046abaaa2c3e9d; deu/dev-00000-of-00001.parquet SHA-256 7534027173b928d39ad19ee90c9824fb0821e84f10441eb4bb300bbeed1ef3f4; deu/test-00000-of-00001.parquet SHA-256 6153fe8d507d5fc8d7f4043c7d1ce41f2fd28aace568461682faa854441f341a; deu/train-00000-of-00001.parquet SHA-256 1db70707040e77c7f2b6f7530885ba74c17334cd33dccb527041d58c43530498; eng/dev-00000-of-00001.parquet SHA-256 82e6a08eebef0c1cf2b6fc30497b746b38566686e1468e1ff03c052c52c037ca; eng/test-00000-of-00001.parquet SHA-256 d2ffa29d9f55227c4630a6287f1ac76325047abf2c21d7431e04190cc22c3548; eng/train-00000-of-00001.parquet SHA-256 b6d668f4bc0081c22e5b4be0679498709676acbf6288a2d81037ab01ac1e28f8; esp/dev-00000-of-00001.parquet SHA-256 8d50aa3e0e9099af800088a3912e52faa066cd3bba09d2b01d6ac9978d4276f7; esp/test-00000-of-00001.parquet SHA-256 aca2327c3bb63493759a39539e569540eec7b9c816e898ceb9b57a1f8686da0b; esp/train-00000-of-00001.parquet SHA-256 89bda45d0e5c08227c1c32c5a3a0b9b8eaef4215ed9ccf619fbea9423ed27968; hin/dev-00000-of-00001.parquet SHA-256 598d9ca26f20b20ab4226de4296099f6c65487aad9cda893fcb1b599fbd2ba2e; hin/test-00000-of-00001.parquet SHA-256 88140ab209f0e1b0b13fab2cdbecdcb1e363d6caaebe106357ac15c8953ae0b4; hin/train-00000-of-00001.parquet SHA-256 07c627191206781e799926f7cc38ba4590cfcec629f7e375efdbc4e04796645a; ind/dev-00000-of-00001.parquet SHA-256 82b5fa81d2afa1cb6c949f33be5deee53ef4b2bb83550e47b515f7277c056c43; ind/test-00000-of-00001.parquet SHA-256 392a3861b30fee4eb9aa395367a7d384a42d98329b59fd1d9d769817d69267b8; ptbr/dev-00000-of-00001.parquet SHA-256 b33aeba9913bcbd06cb258c4e6f7eaaa6fc758389d92b104ef14d0dda20a9c61; ptbr/test-00000-of-00001.parquet SHA-256 a5f447945c472c8ad96263b2d87087a040e7ca809dd1ff784dd3dcdf2a15d6b2; ptbr/train-00000-of-00001.parquet SHA-256 dd3e4cb0df63e30d96f9ae8378eaf50208a48c8fb6cc6b60b58d2adda39d32e2; rus/dev-00000-of-00001.parquet SHA-256 44b26790b8699b5a0bf860b40dc2dbb6a30d1f8e890c12706c0e69e2b22100ab; rus/test-00000-of-00001.parquet SHA-256 0fbadac53f28dd8f2652bc13c90221af3bcbc3236d92efdaeb2fed511225a64f; rus/train-00000-of-00001.parquet SHA-256 10fcc51921a451adb6064c9bd04e08bbf10c43afca4844d2958c5cce6caade6d | the texts only, without their emotion labels (and what every public source goes through, below) |
| clinc150 | training data | CC BY 3.0 | https://creativecommons.org/licenses/by/3.0/legalcode | — | CLINC150 (Larson et al., 2019), https://github.com/clinc/oos-eval | LICENSE SHA-256 e6bc9e9c474700b708f568bac9e5a8a9bcb2b1dad53442f5ba449fcb848b8e76; data_full.json SHA-256 36923c3705a59e08fe9c3883d8bc2dd966ef93e22cb78ac41171782a698d56e0 | the queries of every split only; marked out of scope; lines naming a health word dropped (and what every public source goes through, below) |
| goemotions | training data | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 | — | GoEmotions, Google Research (Demszky et al., 2020) | simplified/test-00000-of-00001.parquet SHA-256 fd0953e535ba2569edc6a1daaa1133f8e4b9071691d540c9bab812fda132bc26; simplified/train-00000-of-00001.parquet SHA-256 b7d74279616ae7c9b8374ab62ea9f9d6504d36a577bb17f745d720dc2b0d4e76; simplified/validation-00000-of-00001.parquet SHA-256 d46ad5633c4fa41829d22d549743a7bf858d94129536a02af7280c281db5e63a | the texts only, without their emotion labels or authors; texts holding the [NAME] or [RELIGION] placeholder dropped (and what every public source goes through, below) |
| massive | training data | CC BY 4.0 | https://creativecommons.org/licenses/by/4.0/legalcode | Copyright Amazon.com Inc. or its affiliates. | MASSIVE 1.1, Amazon (FitzGerald et al., 2022), https://github.com/alexa/massive | amazon-massive-dataset-1.1.tar.gz SHA-256 4cba5faa11c71437928e17cb1b9b3d8b8e727e7ea363a3a9a8045e19c0491577 | the utterances only, the general scenario's left out; marked out of scope; lines naming a health word of the seed lexicon dropped (and what every public source goes through, below) |
| medquad | training data | CC BY 4.0 | https://creativecommons.org/licenses/by/4.0/legalcode | — | MedQuAD (Ben Abacha and Demner-Fushman, 2019), questions only | LICENSE.txt SHA-256 5b63c5abb5f4218f79230730b3733ce681383e2d656044069b8c73cbdb8e7fb7; MedQuAD.zip SHA-256 161d948f8ce8f82accca1f5769a512dff2a01af2b3004d48f2b3da2758a38a09 | the questions only, never an answer; marked as questions (and what every public source goes through, below) |
| symptom_to_diagnosis | training data | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 | — | gretelai/symptom_to_diagnosis (the diagnosis column is not taken) | test.jsonl SHA-256 7581c68031d7433fcce22c9aac08f35595b67402ceef92a7dd7336207f3b6195; train.jsonl SHA-256 9b8ff27dab30cdeb194d6581dcebffe0d91e21921f974f091736c1f25cad283c | the descriptions (input_text) only; the diagnosis column is never read (and what every public source goes through, below) |
| tatoeba | training data | CC BY 2.0 FR per sentence (CC0 where its author chose it) | https://creativecommons.org/licenses/by/2.0/fr/legalcode https://creativecommons.org/publicdomain/zero/1.0/legalcode | — | Tatoeba, https://tatoeba.org (each row keeps its sentence id: https://tatoeba.org/en/sentences/show/<id>); the author of every sentence: attribution/tatoeba.tsv (16000 sentences, SHA-256 9a2109e6efa938b9175c3e13f57265994dc4ac4e20914bce10a3c6899758f43a) | ara_sentences.tsv.bz2 SHA-256 ade96f2fe5ec2a0c3e63edb717886fb96ce09333e2e716f32721de6da7882d23; ara_sentences_CC0.tsv.bz2 SHA-256 8537d5cce4e878b588d9124f9bf26582c98002681accbcaf69c3927ed43c8364; ara_sentences_detailed.tsv.bz2 SHA-256 12dfa352fcac4621448127411c2929fe5445095df3bc130257f05cbf92f02f4e; ces_sentences.tsv.bz2 SHA-256 4086eda49a1416d0f6ea9948b9548b2849aa8cb6de5dc990777a334a744576d7; ces_sentences_CC0.tsv.bz2 SHA-256 2ef3349a40563e9c0d80e48cfb61fc3b3d0e2c179956ef696b53cc161e5f7cae; ces_sentences_detailed.tsv.bz2 SHA-256 774b9788a596768f9aab56c64b540eee9144e31b84ace92068a7b6566fedf535; cmn_sentences.tsv.bz2 SHA-256 f907d1928eeaaaee75bac1987fb3349bd79c96caa8f1467b58cca042aa99a6aa; cmn_sentences_CC0.tsv.bz2 SHA-256 72c6ac699497cbc9b75f6c021ef16dfaa91ab760fbb407217902b6b5388401c0; cmn_sentences_detailed.tsv.bz2 SHA-256 934fdfad76366c1089d8aa4359d0cb57c03950375da8ab265ecb7bf5649ca4ba; deu_sentences.tsv.bz2 SHA-256 d44d01f9b4977ae5fb9a9f9a38c27621c278cdc850a483715bd0cee65686056a; deu_sentences_CC0.tsv.bz2 SHA-256 df8c16713e5715a0e1343bc1195004463e7facee08863d3cd709cbf123a74587; deu_sentences_detailed.tsv.bz2 SHA-256 4fc1b9c2c7ada0f8aebdc8a68532c69b79a483629872f1f3f0ab88f5c50c0772; eng_sentences.tsv.bz2 SHA-256 5f7ebf552bd3925dfd33236ccb6a210c5ba02b365e4a39964fb658e1209e87d9; eng_sentences_CC0.tsv.bz2 SHA-256 a51307f6eccb2ca558a4bcc66610c7aa818ddabdf5cfff40c3bb72cd2a1501c9; eng_sentences_detailed.tsv.bz2 SHA-256 d2c8fc271db70967cbf6e74069b34f394eccccca4a61fc84039e0f0549016ee8; fra_sentences.tsv.bz2 SHA-256 35642092ea1de824882942c3519860cf900109d3921160e65482f64031bea13a; fra_sentences_CC0.tsv.bz2 SHA-256 cf4509f2f3fd542222be2cf085d0d814ce3f9405263c5746bcc965d7e83dfd08; fra_sentences_detailed.tsv.bz2 SHA-256 a81066083bb1f6fe8404e5c835afd22d562df02cd827330838e4f1fa7589011c; hin_sentences.tsv.bz2 SHA-256 54064a02179d57e3b9db41a5990cdfd68205db77ef05e2e4e03f53be93df6f61; hin_sentences_CC0.tsv.bz2 SHA-256 8a60213be89da1e41d4c6cce23cb44c2afc6aa2cdb91a4539e01ba2f86c717cd; hin_sentences_detailed.tsv.bz2 SHA-256 ade327f6ee842e10d0f536ebad9a330d32bda3010b859f86a682c40d8d02339d; ind_sentences.tsv.bz2 SHA-256 7d09916e3c96fb98a676a17990151657e1b7ef2fb728f0e1c1d861eab1f7bd13; ind_sentences_detailed.tsv.bz2 SHA-256 7f5baf08954275d0c4a35d6d778aca20a898ec2b096cf5baa3f3cc4150cd9d03; ita_sentences.tsv.bz2 SHA-256 1983fa71f06d5d46a765eb77655d6cd9a98e7fe174e1971ff471f60a3076fa21; ita_sentences_CC0.tsv.bz2 SHA-256 3c73ed486b6a8b97044ea80063365612596e6e5ea1f143362ccb0725a84eb366; ita_sentences_detailed.tsv.bz2 SHA-256 f6b33194b3e8e495d96b984ea07ff53a727fee15c36302e6f1fe55c6e8aeb4a5; jpn_sentences.tsv.bz2 SHA-256 dd942845be225766229fa6391cbb7796c6b8bdcaea38809e65720cc406dace93; jpn_sentences_CC0.tsv.bz2 SHA-256 39e6768de61f901a0904d9e74e554772d4e12286121ec3e2c6bd5307ce543465; jpn_sentences_detailed.tsv.bz2 SHA-256 591b040bce75503a3a41d51281cdc45ab5390505088b67b52020b7ecc59d24dd; kor_sentences.tsv.bz2 SHA-256 a1aa17c42d7c22c9c83a5e4f2259fd254ac208162cb29a3b53922847e7ec5d6a; kor_sentences_detailed.tsv.bz2 SHA-256 c35af428c111d7e999329d4572ba4a1bc8cc5f6190432ca7686e850180ad8318; pol_sentences.tsv.bz2 SHA-256 4d916109e3ac4c8a23abb7bcf2d97ceca9167e9a399d01fc7b730b7378cde801; pol_sentences_CC0.tsv.bz2 SHA-256 f950e1cb4a920be95db757585935563210954da69e390f5938459e474583b5da; pol_sentences_detailed.tsv.bz2 SHA-256 cd9c63b144ba0e53c38613c534ad48b31cb8154b580bee597e1778e031c3ae7b; por_sentences.tsv.bz2 SHA-256 9d7a138f6cc512131c6daab4bb60c36d5fdbc3f256878917b196c530863d8c94; por_sentences_CC0.tsv.bz2 SHA-256 35f9f11a3a2d25e5ebeade3f5d959f6914314f353f45e2b7e5792d0d3e7d7fdd; por_sentences_detailed.tsv.bz2 SHA-256 52445f35c04ae2f419cad2289dc3395299e28226522c1920f8e754e0f5bbdc1a; rus_sentences.tsv.bz2 SHA-256 e3cd7877e41a9e4b4b77c09975441ae62b8e7403b5a23596f8e92d5b8a24d8bf; rus_sentences_CC0.tsv.bz2 SHA-256 918d970afa904738bd1ee795bbea8fa1fac86f367c5c9b12a72c71aa7fe542a7; rus_sentences_detailed.tsv.bz2 SHA-256 84affb0829f9d091ee52c192e45445f233a2733f39b93d7a10ed28524e36b22e; spa_sentences.tsv.bz2 SHA-256 aef78e2e2d383f32d15beee1e406ed12291dcd72c1c2bfbfe2d8c27868516439; spa_sentences_CC0.tsv.bz2 SHA-256 e8cecab4e084fa4d9843f66c1dab90c5747057e0bc63a758c406235297b636c7; spa_sentences_detailed.tsv.bz2 SHA-256 b891233d60d1f74c025e837790c282b8cf346dfb09f26c6a8636a72548f5de43; tur_sentences.tsv.bz2 SHA-256 b41ff083d48834af8522887e312407e27b7e9e426703965a5b841ec88a02d560; tur_sentences_detailed.tsv.bz2 SHA-256 5589b8871a5e9b13426eff66bbfa19c5bea5c35b886ce283784476ad21e5fd9c | the sentences, each with its author and its own licence (attribution/tatoeba.tsv) (and what every public source goes through, below) |
| tatoeba | vocabulary choice | CC BY 2.0 FR (CC0 1.0 where its author chose it) | https://creativecommons.org/licenses/by/2.0/fr/legalcode | — | Tatoeba, https://tatoeba.org (each row keeps its sentence id: https://tatoeba.org/en/sentences/show/<id>) | 16 languages | counted only, to choose which pieces stay: none of its text is in the reader |
| massive | vocabulary choice | CC BY 4.0 | https://creativecommons.org/licenses/by/4.0/legalcode | — | MASSIVE 1.1, Amazon (FitzGerald et al., 2022), https://github.com/alexa/massive | 15 languages | counted only, to choose which pieces stay: none of its text is in the reader |
| aya | vocabulary choice | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0 | — | Aya Dataset, Cohere For AI (Singh et al., 2024) | 15 languages | counted only, to choose which pieces stay: none of its text is in the reader |
| wikidata | vocabulary choice | CC0 1.0 | https://creativecommons.org/publicdomain/zero/1.0/legalcode | — | Wikidata, https://www.wikidata.org | 16 languages | counted only, to choose which pieces stay: none of its text is in the reader |
| Qwen3.5-122B-A10B-FP8: Qwen/Qwen3.5-122B-A10B-FP8 | outputs only | license: apache-2.0 | https://huggingface.co/Qwen/Qwen3.5-122B-A10B-FP8/blob/main/LICENSE (the repo's own LICENSE) | — | — | Qwen/Qwen3.5-122B-A10B-FP8@a099dee70ccfcd8d5dda56aaa0b60cb8ecadabc9 | its outputs only (generated rows or labels): none of its weights ships |
| Qwen3.5-27B-FP8: Qwen/Qwen3.5-27B-FP8 | outputs only | license: apache-2.0 | https://huggingface.co/Qwen/Qwen3.5-27B-FP8/blob/main/LICENSE (the repo's own LICENSE) | — | — | Qwen/Qwen3.5-27B-FP8@97f5941bf617e31c5e237364a8602ce3f03a551a | its outputs only (generated rows or labels): none of its weights ships |
| gemma-4-31B-it: google/gemma-4-31B-it | outputs only | license: apache-2.0 | https://ai.google.dev/gemma/docs/gemma_4_license (fetched with the weights from https://ai.google.dev/gemma/apache_2: LICENSE.link.html, SHA-256 e3624ba61d4f89a7d9ecf57e5efa42857f463d185bfcb521767c5cacb9abcc6f) | — | — | google/gemma-4-31B-it@842da3794eaa0b77d5f08bae87a17459d91ff475 | its outputs only (generated rows or labels): none of its weights ships |
| mdeberta-v3-base: microsoft/mdeberta-v3-base | outputs only | license: mit | — | — | — | microsoft/mdeberta-v3-base@a0484667b22365f84929a935b5e50a51f71f159d | its outputs only (generated rows or labels): none of its weights ships |
| served primary: Qwen/Qwen3.5-122B-A10B-FP8 | outputs only | license: apache-2.0 | https://huggingface.co/Qwen/Qwen3.5-122B-A10B-FP8/blob/main/LICENSE (the repo's own LICENSE) | — | — | Qwen/Qwen3.5-122B-A10B-FP8@a099dee70ccfcd8d5dda56aaa0b60cb8ecadabc9 | its outputs only (generated rows or labels): none of its weights ships |
| served secondary: google/gemma-4-31B-it | outputs only | license: apache-2.0 | https://ai.google.dev/gemma/docs/gemma_4_license (fetched with the weights from https://ai.google.dev/gemma/apache_2: LICENSE.link.html, SHA-256 e3624ba61d4f89a7d9ecf57e5efa42857f463d185bfcb521767c5cacb9abcc6f) | — | — | google/gemma-4-31B-it@842da3794eaa0b77d5f08bae87a17459d91ff475 | its outputs only (generated rows or labels): none of its weights ships |
| xlm-roberta-large: FacebookAI/xlm-roberta-large | outputs only | license: mit | — | — | — | FacebookAI/xlm-roberta-large@c23d21b0620b635a76227c604d44e43a9f0ee389 | its outputs only (generated rows or labels): none of its weights ships |

Every public source's text also goes through this: whitespace folded and Unicode NFC; filtered by length, personal data, language and duplicates; sampled to at most a fixed number of rows per language; topics and safety read by two open models, rows they disagreed on dropped.

MASSIVE's NOTICE.md, as the dataset ships it (Copyright Amazon.com Inc. or its affiliates.; its LICENSE SHA-256 c2e6ea015269147de02117ebdd91f30ef09831251f5345fa8365273b1db1d435):

> # NOTICE
>
> MASSIVE was created by translating and localizing the English text data from SLURP into 50 languages. Additionally, some of the text data from SLURP is included as part of MASSIVE. SLURP is licensed under CC BY 4.0. The license can be found here:
>
> > [https://github.com/pswietojanski/slurp/blob/master/LICENSE.txt](https://github.com/pswietojanski/slurp/blob/master/LICENSE.txt)
>
> For additional information on SLURP, please see [their paper](https://aclanthology.org/2020.emnlp-main.588.pdf).

Tatoeba: the author of every sentence the data holds is in attribution/tatoeba.tsv (16000 sentences; by licence {"CC BY 2.0 FR": 15933, "CC0 1.0": 67}; 446 without an author, their accounts deleted since; SHA-256 9a2109e6efa938b9175c3e13f57265994dc4ac4e20914bce10a3c6899758f43a).

## Licence texts

### Apache-2.0.txt

the Apache License 2.0: Aya, GoEmotions, gretelai/symptom_to_diagnosis; the generators' and the judge's licence. From https://www.apache.org/licenses/LICENSE-2.0.txt on 2026-10-06, SHA-256 cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30.

```text

                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

### MIT-facebookresearch-fairseq.txt

XLM-R (fairseq): its SentencePiece pieces are the tokenizer's (reader-tokenizer.json). From https://raw.githubusercontent.com/facebookresearch/fairseq/e75cff5f2c1d62f12dc911e0bf420025eb1a4e33/LICENSE on 2026-10-06, SHA-256 52412d7bc7ce4157ea628bbaacb8829e0a9cb3c58f57f99176126bc8cf2bfc85.

```text
MIT License

Copyright (c) Facebook, Inc. and its affiliates.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### MIT-microsoft-unilm.txt

Multilingual-MiniLM (microsoft/unilm): the weights the shipped reader starts from. From https://raw.githubusercontent.com/microsoft/unilm/0e31c7c09737df491e7ff74ded19614b884c52b4/LICENSE on 2026-10-06, SHA-256 904dc4d8749877f1dba1cda48200d2462dccbeb7c134d5e4ef6fa75e0198c8fe.

```text
The MIT License (MIT)

Copyright (c) Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Unicode-3.0.txt

Unicode License v3: emoji-test.txt, whose emoji are pieces of the vocabulary (the URL is not versioned: the text as of the date fetched). From https://www.unicode.org/license.txt on 2026-10-06, SHA-256 e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96.

```text
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
```

