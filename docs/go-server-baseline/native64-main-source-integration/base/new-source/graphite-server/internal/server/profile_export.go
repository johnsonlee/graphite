package server

// Diagnostic-only bridge: call the production response serializer unchanged.
func ProfileEncodeCypherResponse(value any) ([]byte, error) { return encodeCypherResponse(value) }
