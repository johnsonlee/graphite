(function(root, factory) {
    var api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    root.GraphiteUiState = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    function graphCount(info, graphs) {
        return Number(info.count ?? graphs.length);
    }

    async function runRecoverable(action, onError) {
        try {
            return await action();
        } catch (error) {
            onError(error);
            return undefined;
        }
    }

    function createLatestTaskRunner() {
        var generation = 0;
        var activeController;

        function cancel() {
            generation += 1;
            if (activeController) activeController.abort();
            activeController = undefined;
        }

        async function run(task, apply, reject) {
            cancel();
            var requestGeneration = generation;
            var controller = new AbortController();
            activeController = controller;
            try {
                var value = await task(controller.signal);
                if (requestGeneration !== generation || controller.signal.aborted) return false;
                apply(value);
                return true;
            } catch (error) {
                if (requestGeneration !== generation || controller.signal.aborted || error.name === 'AbortError') {
                    return false;
                }
                if (reject) reject(error);
                return false;
            } finally {
                if (requestGeneration === generation) activeController = undefined;
            }
        }

        return { cancel: cancel, run: run };
    }

    return {
        graphCount: graphCount,
        runRecoverable: runRecoverable,
        createLatestTaskRunner: createLatestTaskRunner
    };
}));
