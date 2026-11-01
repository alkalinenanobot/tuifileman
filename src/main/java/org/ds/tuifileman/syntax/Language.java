package org.ds.tuifileman.syntax;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.CPlusPlusTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.CSSTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.CSharpTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.CTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.DartTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.DockerTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.GoTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.GroovyTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.HTMLTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.IniTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.JavaScriptTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.JavaTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.JsonTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.KotlinTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.LatexTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.LessTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.LuaTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.MakefileTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.MarkdownTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.PHPTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.PerlTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.PowerShellTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.PropertiesFileTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.ProtoTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.PythonTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.RubyTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.RustTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.SQLTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.ScalaTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.TypeScriptTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.UnixShellTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.WindowsBatchTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.XMLTokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.YamlTokenMaker;

/**
 * A language the viewer and the editor can colour, picked by file name. The scanners are those of
 * RSyntaxTextArea (used without any Swing component); they are created directly, never by class name, so the
 * native image needs no reflection for them.
 */
public final class Language {
    private static final Map<String, Language> BY_EXTENSION = new HashMap<>();
    private static final Map<String, Language> BY_NAME = new HashMap<>();

    static {
        register("Java", JavaTokenMaker::new, "java");
        register("C", CTokenMaker::new, "c", "h");
        register("C++", CPlusPlusTokenMaker::new, "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "ino");
        register("C#", CSharpTokenMaker::new, "cs");
        register("JavaScript", JavaScriptTokenMaker::new, "js", "mjs", "cjs", "jsx");
        register("TypeScript", TypeScriptTokenMaker::new, "ts", "tsx", "mts", "cts");
        register("Python", PythonTokenMaker::new, "py", "pyw", "pyi");
        register("JSON", JsonTokenMaker::new, "json", "jsonc");
        register("XML", XMLTokenMaker::new, "xml", "xsd", "xsl", "xslt", "svg", "wsdl", "plist", "fxml", "xaml",
                "csproj", "vcxproj", "props", "targets", "resx");
        register("HTML", HTMLTokenMaker::new, "html", "htm", "xhtml");
        register("CSS", CSSTokenMaker::new, "css");
        register("Less", LessTokenMaker::new, "less");
        register("YAML", YamlTokenMaker::new, "yaml", "yml");
        register("Properties", PropertiesFileTokenMaker::new, "properties");
        register("INI", IniTokenMaker::new, "ini", "cfg", "inf", "toml");
        register("Shell", UnixShellTokenMaker::new, "sh", "bash", "zsh", "ksh");
        register("Batch", WindowsBatchTokenMaker::new, "bat", "cmd");
        register("PowerShell", PowerShellTokenMaker::new, "ps1", "psm1", "psd1");
        register("SQL", SQLTokenMaker::new, "sql");
        register("Markdown", MarkdownTokenMaker::new, "md", "markdown", "mdown", "mkd");
        register("Groovy", GroovyTokenMaker::new, "groovy", "gradle", "gvy");
        register("Kotlin", KotlinTokenMaker::new, "kt", "kts");
        register("Scala", ScalaTokenMaker::new, "scala", "sc");
        register("Go", GoTokenMaker::new, "go");
        register("Rust", RustTokenMaker::new, "rs");
        register("PHP", PHPTokenMaker::new, "php");
        register("Ruby", RubyTokenMaker::new, "rb", "rake", "gemspec");
        register("Perl", PerlTokenMaker::new, "pl", "pm");
        register("Lua", LuaTokenMaker::new, "lua");
        register("Dart", DartTokenMaker::new, "dart");
        register("LaTeX", LatexTokenMaker::new, "tex", "sty", "cls");
        register("Protobuf", ProtoTokenMaker::new, "proto");
        Language makefile = register("Makefile", MakefileTokenMaker::new, "mk");
        BY_NAME.put("makefile", makefile);
        BY_NAME.put("gnumakefile", makefile);
        Language docker = register("Dockerfile", DockerTokenMaker::new, "dockerfile");
        BY_NAME.put("dockerfile", docker);
    }

    private final String name;
    private final Supplier<TokenMaker> maker;

    private Language(String name, Supplier<TokenMaker> maker) {
        this.name = name;
        this.maker = maker;
    }

    private static Language register(String name, Supplier<TokenMaker> maker, String... extensions) {
        Language language = new Language(name, maker);
        for (String extension : extensions) {
            BY_EXTENSION.put(extension, language);
        }
        return language;
    }

    /** The language of a file by its name or extension (case-insensitive), or null when it is not one we colour. */
    public static Language forFile(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return null;
        }
        String file = fileName.toString().toLowerCase(Locale.ROOT);
        Language byName = BY_NAME.get(file);
        if (byName != null) {
            return byName;
        }
        int dot = file.lastIndexOf('.');
        return dot < 0 ? null : BY_EXTENSION.get(file.substring(dot + 1));
    }

    /** Display name, for example {@code C++}. */
    public String name() {
        return name;
    }

    /** A new scanner; they keep state while scanning, so every document needs its own. */
    TokenMaker newTokenMaker() {
        return maker.get();
    }
}
