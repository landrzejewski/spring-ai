package pl.training.agents;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.training.agents.chain.Chain;
import pl.training.agents.chain.CodeReviewChainResult;
import pl.training.agents.evaluator.EvaluationCriteria;
import pl.training.agents.evaluator.EvaluatorOptimizer;
import pl.training.agents.evaluator.OptimizedCodeResult;
import pl.training.agents.orchestrator.Orchestrator;
import pl.training.agents.orchestrator.ProjectPlanResult;
import pl.training.agents.orchestrator.WorkerTask;
import pl.training.agents.parallelization.CodeAnalysisRequest;
import pl.training.agents.parallelization.CodeAnalysisResult;
import pl.training.agents.parallelization.ParallelizerAction;
import pl.training.agents.router.Router;
import pl.training.agents.router.TicketCategory;
import pl.training.agents.router.TicketRoutingResult;
import pl.training.model.PromptRequest;

import java.util.List;

/**
 * Agentic workflow patterns, implemented on top of the plain ChatClient API.
 * <p>
 * An AI agent is a system in which the LLM directs the flow of work instead of following a fixed
 * script. Anthropic's "Building Effective Agents" distinguishes three levels:
 * <ol>
 *   <li>Augmented LLM - a model with retrieval, tools and memory;</li>
 *   <li>Agentic workflows - the model steers a flow built from predefined patterns;</li>
 *   <li>Autonomous agents - the model decides everything, including its own control flow.</li>
 * </ol>
 * This controller implements <b>level 2</b>. Nothing here is a Spring AI feature: every pattern is
 * assembled from ChatClient calls and structured output, which is exactly the point - once the
 * model can return typed objects reliably, the orchestration is ordinary Java.
 *
 * <h2>The five patterns</h2>
 * <ol>
 *   <li><b>Prompt chaining</b> - the task is split into a sequence of steps and the output of step
 *       N becomes the input of step N+1. Each step gets a narrow, specialised prompt.
 *       Example: analyse code -&gt; suggest improvements -&gt; refactor. {@code POST /agents/chain}</li>
 *   <li><b>Routing</b> - the model classifies the input and a specialised handler takes over.
 *       Unlike keyword rules it understands context and phrasing. {@code POST /agents/route}</li>
 *   <li><b>Parallelization</b> - independent subtasks run concurrently and the results are
 *       aggregated. Model calls are IO-bound, so virtual threads fit naturally.
 *       {@code POST /agents/parallel}</li>
 *   <li><b>Orchestrator-workers</b> - one model call decomposes the request into tasks with
 *       dependencies, workers execute them, and the orchestrator integrates the results. The task
 *       list is decided at runtime, not hard-coded. {@code POST /agents/orchestrate}</li>
 *   <li><b>Evaluator-optimizer</b> - generate, score against explicit criteria, improve, repeat
 *       until the score is good enough or the iteration budget runs out.
 *       {@code POST /agents/optimize}</li>
 * </ol>
 * Compare the last one with Spring AI's own Evaluator API (RelevancyEvaluator,
 * FactCheckingEvaluator, exercised in {@code src/test/java/.../evaluation}): the same
 * generate-evaluate-improve idea, but with the criteria and the scoring provided by the framework.
 * <p>
 * Request bodies for all five endpoints are in client.http.
 *
 * @see Chain
 * @see Router
 * @see ParallelizerAction
 * @see Orchestrator
 * @see EvaluatorOptimizer
 */
@RestController
@RequestMapping("agents")
public class AgentsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentsController.class);

    private final ChatClient chatClient;
    private final Chain<String, CodeReviewChainResult> codeReviewChain;
    private final Router ticketRouter;
    private final ParallelizerAction<CodeAnalysisRequest, CodeAnalysisResult> codeAnalyzer;
    private final Orchestrator projectOrchestrator;
    private final EvaluatorOptimizer codeOptimizer;

    public AgentsController(OpenAiChatModel chatModel, ObservationRegistry observationRegistry) {
        this.chatClient = ChatClient.builder(chatModel, observationRegistry, null, null).build();

        // Prompt chaining: analyse -> improve -> refactor
        this.codeReviewChain = buildCodeReviewChain();

        // Routing: classify a support ticket, then dispatch to a specialised handler
        this.ticketRouter = buildTicketRouter();

        // Parallelization: three independent analyses, aggregated
        this.codeAnalyzer = buildCodeAnalyzer();

        // Orchestrator-workers: the model decomposes the request at runtime
        this.projectOrchestrator = buildProjectOrchestrator();

        // Evaluator-optimizer: generate, score, improve, repeat
        this.codeOptimizer = buildCodeOptimizer();
    }

    // ==================== PROMPT CHAINING ====================

    /**
     * Prompt chaining. A code review split into three steps:
     * ANALYZE (find the problems) -> IMPROVE (propose a fix for each) -> REFACTOR (apply them).
     * <p>
     * Each step feeds its structured output into the next one as context. The gain is
     * specialisation: three narrow prompts outperform one prompt asked to do all three jobs, and
     * each intermediate result is inspectable, which a single opaque call is not.
     *
     * @param promptRequest source code to review, in userPromptText
     * @return the analysis, the suggestions and the refactored code
     */
    @PostMapping("chain")
    public CodeReviewChainResult chainedCodeReview(@RequestBody PromptRequest promptRequest) {
        LOGGER.info("Chain endpoint called");
        return codeReviewChain.execute(promptRequest.userPromptText());
    }

    private Chain<String, CodeReviewChainResult> buildCodeReviewChain() {
        // Step 1: find the problems
        Action<String, AnalysisStep> analyzeAction = code -> chatClient.prompt()
                .system("""
                        You are a code analysis expert.
                        Analyze the given code and identify:
                        - Code smells (long methods, duplication, magic numbers, etc.)
                        - Potential bugs or edge cases not handled
                        - Violations of best practices and design principles
                        - Performance concerns

                        Return a structured analysis with the original code and a list of findings.
                        Each finding should be specific and actionable.
                        """)
                .user(code)
                .call()
                .entity(AnalysisStep.class);

        // Step 2: propose a fix for each problem found in step 1
        Action<AnalysisStep, ImprovementStep> improveAction = analysis -> chatClient.prompt()
                .system("""
                        You are a software improvement expert.
                        Based on the code analysis findings, suggest specific improvements.
                        For each finding, provide a concrete suggestion on how to fix it.

                        Be specific - mention exact changes needed, not general advice.
                        """)
                .user("Original code:\n" + analysis.originalCode() +
                        "\n\nAnalysis findings:\n" + String.join("\n- ", analysis.findings()))
                .call()
                .entity(ImprovementStep.class);

        // Step 3: apply the suggestions and emit the refactored code
        Action<ImprovementStep, CodeReviewChainResult> refactorAction = improvements ->
                chatClient.prompt()
                        .system("""
                                You are a refactoring expert.
                                Apply the suggested improvements to refactor the code.
                                Return:
                                - originalCode: the original code (unchanged)
                                - analysisFindings: list of problems found
                                - improvementSuggestions: list of improvements suggested
                                - refactoredCode: the improved code
                                - summary: brief summary of what was changed

                                Ensure the refactored code compiles and follows best practices.
                                """)
                        .user("Original code:\n" + improvements.originalCode() +
                                "\n\nImprovements to apply:\n" + String.join("\n- ", improvements.suggestions()))
                        .call()
                        .entity(CodeReviewChainResult.class);

        return Chain.<String>builder()
                .addStep("analyze", "Identify problems in code", analyzeAction)
                .addStep("improve", "Suggest improvements for each problem", improveAction)
                .addStep("refactor", "Generate refactored code", refactorAction)
                .build();
    }

    // Intermediate structured-output types, one per chain step
    private record AnalysisStep(String originalCode, List<String> findings) {}
    private record ImprovementStep(String originalCode, List<String> suggestions) {}

    // ==================== ROUTING ====================

    /**
     * Routing. One model call classifies the ticket into BILLING, TECHNICAL, SALES or GENERAL, and
     * a handler with its own system prompt answers it.
     * <p>
     * The classification is structured output, so the category comes back as an enum rather than
     * as text to be parsed. The reason to use a model rather than keyword rules is that it reads
     * intent: "I was charged twice" is billing even though it never says "invoice".
     *
     * @param promptRequest the customer message, in userPromptText
     * @return the category, the model's confidence and reasoning, and the handler's reply
     */
    @PostMapping("route")
    public TicketRoutingResult routeTicket(@RequestBody PromptRequest promptRequest) {
        LOGGER.info("Route endpoint called");
        return ticketRouter.route(promptRequest.userPromptText());
    }

    private Router buildTicketRouter() {
        // BILLING handler - payments, invoices, refunds, subscriptions
        Action<String, String> billingHandler = ticket -> chatClient.prompt()
                .system("""
                        You are a billing support specialist with expertise in:
                        - Payment processing and troubleshooting
                        - Invoice generation and explanations
                        - Subscription management
                        - Refund processing

                        Help the customer with their billing issue.
                        Be professional, empathetic, and offer specific solutions.
                        If a refund is needed, explain the process clearly.
                        """)
                .user(ticket)
                .call()
                .content();

        // TECHNICAL handler - errors, troubleshooting
        Action<String, String> technicalHandler = ticket -> chatClient.prompt()
                .system("""
                        You are a technical support engineer with expertise in:
                        - Troubleshooting application errors
                        - Debugging common issues
                        - System configuration
                        - Performance optimization

                        Help diagnose and resolve the technical issue.
                        Provide step-by-step troubleshooting instructions.
                        If the issue requires escalation, explain next steps.
                        """)
                .user(ticket)
                .call()
                .content();

        // SALES handler - pricing, products, upgrades
        Action<String, String> salesHandler = ticket -> chatClient.prompt()
                .system("""
                        You are a sales representative with expertise in:
                        - Product features and benefits
                        - Pricing plans and comparisons
                        - Upgrade recommendations
                        - Custom enterprise solutions

                        Answer questions about products, pricing, and features.
                        Highlight benefits and suggest appropriate plans.
                        Be helpful and informative, but not pushy.
                        """)
                .user(ticket)
                .call()
                .content();

        // GENERAL handler - everything else
        Action<String, String> generalHandler = ticket -> chatClient.prompt()
                .system("""
                        You are a friendly customer support representative.
                        Handle general inquiries, feedback, and questions.
                        Be helpful and direct the customer to appropriate resources if needed.
                        Thank them for their feedback when appropriate.
                        """)
                .user(ticket)
                .call()
                .content();

        return Router.builder(chatClient)
                .addHandler(TicketCategory.BILLING, billingHandler)
                .addHandler(TicketCategory.TECHNICAL, technicalHandler)
                .addHandler(TicketCategory.SALES, salesHandler)
                .addHandler(TicketCategory.GENERAL, generalHandler)
                .build();
    }

    // ==================== PARALLELIZATION ====================

    /**
     * Parallelization. Three independent analyses of the same code - quality, security and
     * performance - run at the same time and are merged into a single weighted report.
     * <p>
     * The subtasks share no state and none depends on another's output, which is what makes the
     * pattern applicable. Wall-clock time becomes that of the slowest call rather than the sum.
     *
     * @param request the code to analyse (sourceCode, language, context)
     * @return the three analyses plus the aggregated verdict
     */
    @PostMapping("parallel")
    public CodeAnalysisResult parallelCodeAnalysis(@RequestBody CodeAnalysisRequest request) {
        LOGGER.info("Parallel endpoint called");
        return codeAnalyzer.execute(request);
    }

    private ParallelizerAction<CodeAnalysisRequest, CodeAnalysisResult> buildCodeAnalyzer() {
        // Task 1: quality - readability, code smells, structure
        Action<CodeAnalysisRequest, CodeAnalysisResult.QualityAnalysis> qualityAction =
                req -> chatClient.prompt()
                        .system("""
                                You are a code quality expert.
                                Analyze the code for:
                                - Code smells (long methods, duplication, magic numbers)
                                - Readability and documentation
                                - Naming conventions
                                - Code structure and organization
                                - Adherence to coding standards

                                Return:
                                - score: 0.0 (very poor) to 1.0 (excellent)
                                - issues: list of quality issues found
                                - suggestions: list of improvement suggestions
                                """)
                        .user("Language: " + req.language() +
                                "\nContext: " + req.context() +
                                "\n\nCode:\n" + req.sourceCode())
                        .call()
                        .entity(CodeAnalysisResult.QualityAnalysis.class);

        // Task 2: security - injection, data leaks
        Action<CodeAnalysisRequest, CodeAnalysisResult.SecurityAnalysis> securityAction =
                req -> chatClient.prompt()
                        .system("""
                                You are a security expert specialized in code review.
                                Analyze the code for:
                                - SQL injection vulnerabilities
                                - XSS (Cross-Site Scripting) vulnerabilities
                                - Authentication/authorization issues
                                - Data exposure risks
                                - Hardcoded secrets or credentials
                                - Input validation issues

                                Return:
                                - score: 0.0 (critical vulnerabilities) to 1.0 (secure)
                                - vulnerabilities: list of security issues found
                                - severity: overall severity level (LOW, MEDIUM, HIGH, CRITICAL)
                                """)
                        .user("Language: " + req.language() +
                                "\nContext: " + req.context() +
                                "\n\nCode:\n" + req.sourceCode())
                        .call()
                        .entity(CodeAnalysisResult.SecurityAnalysis.class);

        // Task 3: performance - complexity, bottlenecks
        Action<CodeAnalysisRequest, CodeAnalysisResult.PerformanceAnalysis> performanceAction =
                req -> chatClient.prompt()
                        .system("""
                                You are a performance optimization expert.
                                Analyze the code for:
                                - Time complexity issues (Big O)
                                - Memory usage concerns
                                - I/O bottlenecks
                                - N+1 query problems
                                - Caching opportunities
                                - Resource leaks

                                Return:
                                - score: 0.0 (very inefficient) to 1.0 (optimal)
                                - bottlenecks: list of performance issues found
                                - optimizations: list of optimization suggestions
                                """)
                        .user("Language: " + req.language() +
                                "\nContext: " + req.context() +
                                "\n\nCode:\n" + req.sourceCode())
                        .call()
                        .entity(CodeAnalysisResult.PerformanceAnalysis.class);

        // Aggregator - merges the three analyses into one report
        return ParallelizerAction.<CodeAnalysisRequest, CodeAnalysisResult>builder(results -> {
                    var quality = (CodeAnalysisResult.QualityAnalysis) results.get(0);
                    var security = (CodeAnalysisResult.SecurityAnalysis) results.get(1);
                    var performance = (CodeAnalysisResult.PerformanceAnalysis) results.get(2);

                    // Weighted average: 40% quality, 35% security, 25% performance
                    double overallScore = (quality.score() * 0.40 +
                            security.score() * 0.35 +
                            performance.score() * 0.25);

                    return new CodeAnalysisResult(quality, security, performance, overallScore, 0);
                })
                .addTask("quality", qualityAction)
                .addTask("security", securityAction)
                .addTask("performance", performanceAction)
                .build();
    }

    // ==================== ORCHESTRATOR-WORKERS ====================

    /**
     * Orchestrator-workers. A first model call decomposes the request into tasks with declared
     * dependencies, workers execute them in dependency order, and a final call integrates the
     * pieces.
     * <p>
     * What separates this from prompt chaining is that the steps are not known in advance: the
     * model produces the plan. "Build a REST API for todo items" might yield
     * [Todo model, TodoRepository, TodoService, TodoController] for one request and something
     * quite different for the next.
     *
     * @param promptRequest the project description, in userPromptText
     * @return the decomposition, the worker outputs and the integrated result
     */
    @PostMapping("orchestrate")
    public ProjectPlanResult orchestrateProject(@RequestBody PromptRequest promptRequest) {
        LOGGER.info("Orchestrate endpoint called");
        return projectOrchestrator.execute(promptRequest.userPromptText());
    }

    private Orchestrator buildProjectOrchestrator() {
        // What a single worker does with one task from the plan
        Action<WorkerTask, String> workerAction = task -> chatClient.prompt()
                .system("""
                        You are a skilled software developer.
                        Execute the given task thoroughly and provide complete output.

                        Guidelines:
                        - If generating code, make it production-ready
                        - Include proper error handling
                        - Follow best practices for the given language/framework
                        - Add meaningful comments where helpful
                        """)
                .user("Task: " + task.description() + "\n\nContext: " + task.context())
                .call()
                .content();

        return Orchestrator.builder(chatClient)
                .workerAction(workerAction)
                .maxWorkers(5)
                .build();
    }

    // ==================== EVALUATOR-OPTIMIZER ====================

    /**
     * Evaluator-optimizer. Generate, score against explicit weighted criteria (correctness,
     * readability, efficiency, maintainability, security), feed the feedback back to the generator,
     * repeat until the score clears the threshold or the iteration budget is spent.
     * <p>
     * The loop only works because the evaluation is structured output: a numeric score per
     * criterion plus written feedback, which the next generation prompt can actually act on.
     * Spring AI's own Evaluator API applies the same idea to relevancy and fact checking.
     *
     * @param promptRequest a description of the code to generate, in userPromptText
     * @return the iteration history, the final code, the scores and whether it was accepted
     */
    @PostMapping("optimize")
    public OptimizedCodeResult optimizeCode(@RequestBody PromptRequest promptRequest) {
        LOGGER.info("Optimize endpoint called");
        return codeOptimizer.execute(promptRequest.userPromptText());
    }

    private EvaluatorOptimizer buildCodeOptimizer() {
        return EvaluatorOptimizer.builder(chatClient)
                .criteria(EvaluationCriteria.CODE_CRITERIA)
                .maxIterations(3)
                .acceptanceThreshold(0.8)
                .build();
    }
}
