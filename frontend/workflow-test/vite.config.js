import {defineConfig} from "vite";
import react from "@vitejs/plugin-react";
import {resolve} from "node:path";

export default defineConfig({
    base: "/workflow-test/",
    plugins: [react()],
    build: {
        outDir: resolve(import.meta.dirname, "../../src/main/resources/static/workflow-test"),
        emptyOutDir: true,
        rollupOptions: {
            output: {
                entryFileNames: "assets/app.js",
                chunkFileNames: "assets/[name].js",
                assetFileNames: assetInfo => assetInfo.name?.endsWith(".css")
                    ? "assets/styles.css"
                    : "assets/[name][extname]"
            }
        }
    }
});
