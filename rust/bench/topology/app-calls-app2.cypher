MATCH (n:CallSite)
WHERE n.graphId = "app" AND n.callee_class STARTS WITH "java.util"
RETURN "app" AS source, "app2" AS target, "call" AS protocol, n.callee_name AS operation, n.callee_class AS evidence
LIMIT 200
