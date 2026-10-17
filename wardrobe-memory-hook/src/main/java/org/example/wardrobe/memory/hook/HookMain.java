package org.example.wardrobe.memory.hook;

/**
 * Точка входа shell-хука Hermes. В stdout — только итог ({@code {"context": …}} или ничего), код выхода всегда 0.
 */
public final class HookMain {

    private HookMain() {
    }

    public static void main(String[] args) {
        int code = new Hook().run(System.in, System.out, System.getenv());
        System.out.flush();
        // exit сразу: потоки HttpClient не держат процесс после ответа
        System.exit(code);
    }
}
