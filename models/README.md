# 本地中文 embedding 模型 / Local Chinese embedding model

[中文项目说明](../README.md) | [English project guide](../README.en.md)

仓库包含 bge-small-zh-v1.5.onnx（94,851,877 字节，约 90.46 MiB）及匹配的 tokenizer.json，普通 git clone 会下载两者，无需 Git LFS。原开发说明标注模型来自 Xenova/bge-small-zh-v1.5。

This directory includes bge-small-zh-v1.5.onnx (94,851,877 bytes, approximately 90.46 MiB) and its tokenizer.json. Both are ordinary Git files downloaded by git clone; Git LFS is not required. The original development notes identify Xenova/bge-small-zh-v1.5 as the source.

从 backend/ 启动时默认读取 ../models/。可使用 KG_EMBEDDING_MODEL 和 KG_EMBEDDING_TOKENIZER 覆盖路径；当前 embedding 维度为 512。

When started from backend/, the default paths point to ../models/. Override them with KG_EMBEDDING_MODEL and KG_EMBEDDING_TOKENIZER. The configured embedding dimension is 512.
