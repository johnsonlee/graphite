(function(root, factory) {
    var api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    root.GraphiteUiState = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function() {
    function graphCount(info, graphs) {
        return Number(info.count ?? graphs.length);
    }

    function createLatestIntentController() {
        var generation = 0;
        var activeController;

        function cancel() {
            generation += 1;
            if (activeController) activeController.abort();
            activeController = undefined;
        }

        function begin() {
            cancel();
            var requestGeneration = generation;
            var controller = new AbortController();
            activeController = controller;

            function isCurrent() {
                return requestGeneration === generation && !controller.signal.aborted;
            }

            return {
                signal: controller.signal,
                isCurrent: isCurrent,
                commit: function(action) {
                    if (!isCurrent()) return false;
                    action();
                    return true;
                }
            };
        }

        return { begin: begin, cancel: cancel };
    }

    function createInterruptingActionRunner(intentController, onError, onSuccess, onStart) {
        async function run(action) {
            if (onStart) onStart(action);
            var intent = intentController.begin();
            try {
                var result = await action(intent);
                if (intent.isCurrent() && onSuccess) onSuccess(action);
                return result;
            } catch (error) {
                if (!intent.isCurrent() || error.name === 'AbortError') return undefined;
                onError(error, action);
                return undefined;
            }
        }
        run.cancel = intentController.cancel;
        return run;
    }

    return {
        graphCount: graphCount,
        createLatestIntentController: createLatestIntentController,
        createInterruptingActionRunner: createInterruptingActionRunner
    };
}));
