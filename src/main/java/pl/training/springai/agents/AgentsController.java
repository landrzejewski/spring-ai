package pl.training.springai.agents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.training.springai.agents.chain.Chain;
import pl.training.springai.agents.chain.CodeReviewChainResult;
import pl.training.springai.agents.evaluator.EvaluationCriteria;
import pl.training.springai.agents.evaluator.EvaluatorOptimizer;
import pl.training.springai.agents.evaluator.OptimizedCodeResult;
import pl.training.springai.agents.orchestrator.Orchestrator;
import pl.training.springai.agents.orchestrator.ProjectPlanResult;
import pl.training.springai.agents.orchestrator.WorkerTask;
import pl.training.springai.agents.parallelization.CodeAnalysisRequest;
import pl.training.springai.agents.parallelization.CodeAnalysisResult;
import pl.training.springai.agents.parallelization.ParallelizerAction;
import pl.training.springai.agents.router.Router;
import pl.training.springai.agents.router.TicketCategory;
import pl.training.springai.agents.router.TicketRoutingResult;
import pl.training.springai.model.PromptRequest;

import java.util.List;

/**
 *
 * Agenci AI to systemy, w ktorych LLM dynamicznie kieruje przepływem pracy,
 * zamiast podazac statycznym przepływem. Maja wieksza autonomie w podejmowaniu
 * decyzji o sposobie realizacji zadania.
 *
 * Poziomy agentowosci (wg Anthropic):
 * 1. Augmented LLM - LLM z retrieval, tools i memory
 * 2. Agentic Workflows - LLM kieruje przepływem przez predefiniowane wzorce
 * 3. Autonomous Agents - pelna autonomia LLM w podejmowaniu decyzji
 *
 * Ten kontroler implementuje POZIOM 2 - Agentic Workflows.
 *
 * Wzorce agentowe (od prostszych do bardziej zlozonych):
 *
 * 1. PROMPT CHAINING (Lancuch Promptow)
 *    - Zadanie dzielone na sekwencje krokow
 *    - Wyjscie kroku N staje sie wejsciem kroku N+1
 *    - Przyklad: Analiza kodu -> Sugestie -> Refaktoryzacja
 *    - Endpoint: POST /agents/chain
 *
 * 2. ROUTING (Trasowanie)
 *    - LLM klasyfikuje wejscie do kategorii
 *    - Kazda kategoria ma specjalistyczny handler
 *    - Przyklad: Ticket -> [Billing|Technical|Sales] -> Handler
 *    - Endpoint: POST /agents/route
 *
 * 3. PARALLELIZATION (Zrownoleglenie)
 *    - Zadanie dzielone na niezalezne podzadania
 *    - Podzadania wykonywane rownolegle (Virtual Threads)
 *    - Wyniki agregowane
 *    - Przyklad: [Quality|Security|Performance] -> Raport
 *    - Endpoint: POST /agents/parallel
 *
 * 4. ORCHESTRATOR-WORKERS (Orkiestrator-Pracownicy)
 *    - Centralny LLM dynamicznie tworzy podzadania
 *    - Workers wykonuja podzadania (respektujac zaleznosci)
 *    - Orkiestrator integruje wyniki
 *    - Przyklad: "Zbuduj API" -> [Model, Service, Controller] -> Integracja
 *    - Endpoint: POST /agents/orchestrate
 *
 * 5. EVALUATOR-OPTIMIZER (Ewaluator-Optymalizator)
 *    - Generator tworzy rozwiazanie
 *    - Ewaluator ocenia wedlug kryteriow
 *    - Petla iteracyjna az do akceptacji
 *    - Przyklad: Generuj kod -> Ocen -> Popraw -> ... -> Akceptacja
 *    - Endpoint: POST /agents/optimize
 *
 * Przyklad uzycia z curl:
 * <pre>
 * # Chain (Code Review)
 * curl -X POST localhost:8080/agents/chain \
 *   -H "Content-Type: application/json" \
 *   -d '{"message": "public void foo() { int x = 1; return x; }"}'
 *
 * # Route (Ticket Classification)
 * curl -X POST localhost:8080/agents/route \
 *   -H "Content-Type: application/json" \
 *   -d '{"message": "I cannot login to my account"}'
 *
 * # Parallel (Code Analysis)
 * curl -X POST localhost:8080/agents/parallel \
 *   -H "Content-Type: application/json" \
 *   -d '{"sourceCode": "SELECT * FROM users WHERE id=" + id, "language": "Java", "context": ""}'
 *
 * # Orchestrate (Project Planning)
 * curl -X POST localhost:8080/agents/orchestrate \
 *   -H "Content-Type: application/json" \
 *   -d '{"message": "Build a REST API for todo items"}'
 *
 * # Optimize (Iterative Code Generation)
 * curl -X POST localhost:8080/agents/optimize \
 *   -H "Content-Type: application/json" \
 *   -d '{"message": "Write a function to check if a string is palindrome"}'
 * </pre>
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

    public AgentsController(OpenAiChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();

        // Inicjalizacja Chain dla Code Review
        this.codeReviewChain = buildCodeReviewChain();

        // Inicjalizacja Router dla Ticketow
        this.ticketRouter = buildTicketRouter();

        // Inicjalizacja Parallelizer dla Code Analysis
        this.codeAnalyzer = buildCodeAnalyzer();

        // Inicjalizacja Orchestrator dla Project Planning
        this.projectOrchestrator = buildProjectOrchestrator();

        // Inicjalizacja Evaluator-Optimizer dla Code Generation
        this.codeOptimizer = buildCodeOptimizer();
    }

    // ==================== PROMPT CHAINING ====================

    /**
     * Demonstracja wzorca Prompt Chaining.
     *
     * Lancuch Code Review sklada sie z 3 krokow:
     * 1. ANALYZE: Znajdz problemy w kodzie (code smells, bugs, violations)
     * 2. IMPROVE: Zaproponuj konkretne usprawnienia dla kazdego problemu
     * 3. REFACTOR: Wygeneruj zrefaktoryzowany kod z zastosowanymi poprawkami
     *
     * Kazdy krok uzywa wyniku poprzedniego jako kontekstu.
     * To pozwala na specjalizacje kazdego kroku i lepsza jakosc wyniku.
     *
     * @param promptRequest Kod zrodlowy do review (w polu message)
     * @return Wynik lancucha z analiza, sugestiami i zrefaktoryzowanym kodem
     */
    @PostMapping("chain")
    public CodeReviewChainResult chainedCodeReview(@RequestBody PromptRequest promptRequest) {
        LOGGER.info("Chain endpoint called");
        return codeReviewChain.execute(promptRequest.userPromptText());
    }

    private Chain<String, CodeReviewChainResult> buildCodeReviewChain() {
        // Krok 1: Analiza kodu - znajdz problemy
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

        // Krok 2: Sugestie usprawnien - dla kazdego problemu zaproponuj rozwiazanie
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

        // Krok 3: Refaktoryzacja - zastosuj poprawki i wygeneruj nowy kod
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

    // Wewnetrzne recordy dla krokow lancucha
    private record AnalysisStep(String originalCode, List<String> findings) {}
    private record ImprovementStep(String originalCode, List<String> suggestions) {}

    // ==================== ROUTING ====================

    /**
     * Demonstracja wzorca Routing.
     *
     * Router klasyfikuje zgloszenie i kieruje do specjalisty:
     * - BILLING: Obsluga platnosci, faktur, zwrotow, subskrypcji
     * - TECHNICAL: Obsluga bledow, problemow technicznych, troubleshooting
     * - SALES: Obsluga pytan o cennik, produkty, upgrades
     * - GENERAL: Pozostale pytania, feedback, sugestie
     *
     * LLM rozumie kontekst i niuanse, nie wymaga prostych regul slowo-klucz.
     *
     * @param promptRequest Tresc zgloszenia klienta (w polu message)
     * @return Wynik z klasyfikacja, pewnoscia, uzasadnieniem i odpowiedzia handlera
     */
    @PostMapping("route")
    public TicketRoutingResult routeTicket(@RequestBody PromptRequest promptRequest) {
        LOGGER.info("Route endpoint called");
        return ticketRouter.route(promptRequest.userPromptText());
    }

    private Router buildTicketRouter() {
        // Handler dla BILLING - specjalista od platnosci
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

        // Handler dla TECHNICAL - specjalista techniczny
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

        // Handler dla SALES - specjalista sprzedazy
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

        // Handler dla GENERAL - obsluga ogolna
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
     * Demonstracja wzorca Parallelization.
     *
     * Rownolegle wykonuje 3 niezalezne analizy kodu:
     * - Quality Analysis: Jakosc kodu, czytelnosc, code smells, struktura
     * - Security Analysis: Podatnosci (SQL injection, XSS), wycieki danych
     * - Performance Analysis: Zlozonosc czasowa, waskie gardla, optymalizacje
     *
     * Wyniki sa agregowane w jeden raport z ogolna ocena.
     * Uzywa Virtual Threads dla efektywnej rownoleglej komunikacji z API.
     *
     * @param request Kod do analizy (sourceCode, language, context)
     * @return Zagregowany wynik wszystkich trzech analiz
     */
    @PostMapping("parallel")
    public CodeAnalysisResult parallelCodeAnalysis(@RequestBody CodeAnalysisRequest request) {
        LOGGER.info("Parallel endpoint called");
        return codeAnalyzer.execute(request);
    }

    private ParallelizerAction<CodeAnalysisRequest, CodeAnalysisResult> buildCodeAnalyzer() {
        // Task 1: Quality Analysis - jakosc i czytelnosc kodu
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

        // Task 2: Security Analysis - podatnosci i bezpieczenstwo
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

        // Task 3: Performance Analysis - wydajnosc i optymalizacje
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

        // Aggregator - laczy wyniki trzech analiz
        return ParallelizerAction.<CodeAnalysisRequest, CodeAnalysisResult>builder(results -> {
                    var quality = (CodeAnalysisResult.QualityAnalysis) results.get(0);
                    var security = (CodeAnalysisResult.SecurityAnalysis) results.get(1);
                    var performance = (CodeAnalysisResult.PerformanceAnalysis) results.get(2);

                    // Srednia wazona: 40% quality, 35% security, 25% performance
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
     * Demonstracja wzorca Orchestrator-Workers.
     *
     * Orkiestrator:
     * 1. Analizuje request i tworzy plan (lista taskow z zalezno sciami)
     * 2. Workers wykonuja taski (respektujac kolejnosc zaleznosci)
     * 3. Orkiestrator integruje wyniki w spojny rezultat
     *
     * Przyklad: "Zbuduj REST API dla todo app"
     * -> Orchestrator tworzy: [Todo Model, TodoRepository, TodoService, TodoController]
     * -> Workers generuja kod dla kazdego komponentu
     * -> Orchestrator integruje w kompletna aplikacje
     *
     * @param promptRequest Opis projektu/zadania (w polu message)
     * @return Plan z dekompozycja, wynikami workerow i zintegrowanym rezultatem
     */
    @PostMapping("orchestrate")
    public ProjectPlanResult orchestrateProject(@RequestBody PromptRequest promptRequest) {
        LOGGER.info("Orchestrate endpoint called");
        return projectOrchestrator.execute(promptRequest.userPromptText());
    }

    private Orchestrator buildProjectOrchestrator() {
        // Worker action - jak worker wykonuje pojedynczy task
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
     * Demonstracja wzorca Evaluator-Optimizer.
     *
     * Petla iteracyjna:
     * 1. Generator tworzy kod
     * 2. Ewaluator ocenia wedlug 5 kryteriow (Correctness, Readability, Efficiency, Maintainability, Security)
     * 3. Jesli score < 0.8 -> Generator poprawia kod na podstawie feedbacku
     * 4. Powtarzaj do akceptacji lub max 3 iteracji
     *
     * Pozwala na iteracyjna poprawe jakosci kodu z obiektywna ocena.
     *
     * @param promptRequest Opis funkcji/kodu do wygenerowania (w polu message)
     * @return Historia iteracji, finalny kod, oceny i status akceptacji
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
