# 本地模型文件

本项目使用 bge-small-zh-v1.5 ONNX 模型和 tokenizer.json。
模型文件 bge-small-zh-v1.5.onnx 体积较大，不纳入 Git；当前电脑上的文件继续保留。
在新电脑部署时，请从模型发布方获取相同模型的 ONNX 文件，放到本目录，或通过 KG_EMBEDDING_MODEL 指定路径。
请使用与模型匹配的 tokenizer.json。其他启动步骤见项目根目录 README.md。
