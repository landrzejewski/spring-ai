# Spring AI in a Nutshell

Spring AI is an application framework for AI engineering. It applies the familiar Spring
portability and modular design principles to the AI domain.

## Chat Models

A ChatModel is the low-level abstraction over a provider's completion endpoint. ChatClient is the
fluent facade built on top of it, adding prompt templating, structured output conversion, tool
calling and the advisor chain.

## Retrieval Augmented Generation

RAG grounds a model's answer in your own data. Documents are chunked, embedded and stored in a
vector database. At query time the most similar chunks are retrieved and injected into the prompt,
so the model answers from the retrieved context rather than from memory.

## Tool Calling

Tool calling lets a model request the execution of application code. The model never runs anything
itself: it returns a structured tool-call request, the framework executes the matching callback and
feeds the result back, looping until the reply contains no further tool calls.

## Model Context Protocol

MCP turns tools into a transport-level contract. A separate process exposes its capabilities over
stdio or HTTP, and any MCP-aware client can discover and consume them without compile-time coupling.

## Observability

Spring AI publishes Micrometer observations for every model call, tool invocation and vector store
operation, producing both metrics and distributed tracing spans out of the box.
