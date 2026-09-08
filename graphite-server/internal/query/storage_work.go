package query

// storeWorkConsumer gives a storage preparation owner this request's tracker.
// Return query failures so the owner can finish its loading/cleanup protocol
// before the query boundary restores the original failure. Store must not retain
// this callback on a published index or use it for another request's load.
func (e evaluator) storeWorkConsumer() func(int64) error {
	if e.work == nil {
		return nil
	}
	work := e.work
	return func(units int64) (err error) {
		defer func() {
			if recovered := recover(); recovered != nil {
				if failure, ok := recovered.(*Error); ok {
					err = failure
					return
				}
				panic(recovered)
			}
		}()
		work.consume(units)
		return nil
	}
}
