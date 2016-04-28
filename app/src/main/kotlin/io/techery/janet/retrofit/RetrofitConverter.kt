package io.techery.janet.retrofit

import com.github.javaparser.ASTHelper
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.ast.PackageDeclaration
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import io.techery.janet.body.BytesArrayBody
import io.techery.janet.body.FileBody
import io.techery.janet.http.annotations.*
import io.techery.janet.http.annotations.Body
import io.techery.janet.http.annotations.Field
import io.techery.janet.http.annotations.Part
import io.techery.janet.http.annotations.Path
import io.techery.janet.http.annotations.Query
import okhttp3.RequestBody
import org.kohsuke.args4j.Argument
import org.kohsuke.args4j.CmdLineParser
import org.kohsuke.args4j.Option
import retrofit2.http.*
import java.io.File
import java.io.FileWriter
import java.nio.file.Paths
import java.util.*


fun main(args: Array<String>) {
    val converter = RetrofitConverter()
    CmdLineParser(converter).parseArgument(*args)
    converter.run()
}

class RetrofitConverter {

    @Argument(metaVar = "source file", required = true, usage = "Sets a file of retrofit service")
    var file: File? = null
    @Option(name = "--package")
    var packageName: String? = null
    @Option(name = "--output")
    var output: java.nio.file.Path? = null

    var sourceImports: List<ImportDeclaration>? = null

    fun run() {
        val unit = JavaParser.parse(file)
        if (packageName == null) {
            packageName = unit.`package`.name.name
        }
        sourceImports = unit.imports.filter { !it.name.toString().contains("retrofit") }
        if (output == null) {
            output = Paths.get(System.getProperty("user.dir"))
        }
        object : VoidVisitorAdapter<Void>() {
            override fun visit(method: MethodDeclaration?, arg: Void?) {
                processMethod(method!!)
                super.visit(method, arg)
            }
        }.visit(unit, null)
    }

    fun processMethod(source: MethodDeclaration) {
        var method: HttpAction.Method? = null
        var type = HttpAction.Type.SIMPLE
        var path: Expression? = null
        var headers: Expression? = null
        source.annotations.forEach {
            when (it.name.name) {
                GET::class.simpleName -> method = HttpAction.Method.GET
                POST::class.simpleName -> method = HttpAction.Method.POST
                PUT::class.simpleName -> method = HttpAction.Method.PUT
                DELETE::class.simpleName -> method = HttpAction.Method.DELETE
                HEAD::class.simpleName -> method = HttpAction.Method.HEAD
                PATCH::class.simpleName -> method = HttpAction.Method.PATCH
                FormUrlEncoded::class.simpleName -> type = HttpAction.Type.FORM_URL_ENCODED
                Multipart::class.simpleName -> type = HttpAction.Type.MULTIPART
                Headers::class.simpleName -> headers = it.getValue("value")
            }
            if (path == null && method != null) {
                path = it.getValue("value")
            }
        }
        if (method != null) {
            if (path == null) path = StringLiteralExpr("")
            createUnit(packageName!!, source.name, type, method!!, path!!,
                    headers, source.parameters.toTypedArray(), sourceImports!!,
                    source.type).save(output!!)
            println("Check files before using them!")
        }
    }
}

fun createUnit(packageName: String,
               name: String,
               type: HttpAction.Type,
               method: HttpAction.Method,
               path: Expression,
               headers: Expression?,
               params: Array<Parameter>,
               sourceImports: List<ImportDeclaration>,
               responseType: Type): CompilationUnit {
    val cu = CompilationUnit();
    cu.`package` = PackageDeclaration(ASTHelper.createNameExpr(packageName));
    val imports = mutableListOf(ImportDeclaration(ASTHelper.createNameExpr(HttpAction::class.qualifiedName), false, false))
    val classDeclaration = createActionClassDeclaration(name, type, method, path, headers)
    val constructor = ConstructorDeclaration(ModifierSet.PUBLIC, classDeclaration.name)
    //request fields
    val members = ArrayList<BodyDeclaration>()
    val constructorStmts = ArrayList<Statement>()
    params.forEach {
        val field = it.toJanetField({ imports.addAll(it) })
        members.add(field)
        // add constructor param for field
        val id = field.variables.first().id
        constructor.parameters.add(Parameter(field.type, id));
        constructorStmts.add(ExpressionStmt(NameExpr("this.$id = $id")))
    }
    // add response field
    val responseFieldName = "response";
    if (!responseType.toString().contains("Void")) {
        val field = FieldDeclaration(0, responseType, VariableDeclarator(VariableDeclaratorId(responseFieldName)))
        field.annotations.add(MarkerAnnotationExpr(NameExpr(Response::class.simpleName)))
        imports.add(ImportDeclaration(ASTHelper.createNameExpr(Response::class.qualifiedName), false, false))
        members.add((field))
    }

    constructor.block = BlockStmt(constructorStmts)
    members.add(constructor)

    // add response getter
    val getter = MethodDeclaration(ModifierSet.PUBLIC, responseType, "getResponse")
    getter.body = BlockStmt(listOf(ReturnStmt(NameExpr("this.$responseFieldName"))))
    members.add(getter)

    members.forEach {
        sourceImports.filter { it.name.toString().contains(responseType.toString()) }
                .forEach { imports.add(it) }
        ASTHelper.addMember(classDeclaration, it)
    }

    cu.imports = imports.distinct()

    ASTHelper.addTypeDeclaration(cu, classDeclaration)
    return cu
}

fun createActionClassDeclaration(name: String,
                                 type: HttpAction.Type,
                                 method: HttpAction.Method,
                                 path: Expression,
                                 headers: Expression?): ClassOrInterfaceDeclaration {
    val classDeclaration = ClassOrInterfaceDeclaration(ModifierSet.PUBLIC, false, name.capitalize().plus("Action"))
    val actionName = NameExpr(HttpAction::class.simpleName)
    var annotation: AnnotationExpr
    if (method != HttpAction.Method.GET || headers != null || type != HttpAction.Type.SIMPLE) {
        val pairs = ArrayList<MemberValuePair>()
        pairs.add(MemberValuePair("value", path))
        if (headers != null)
            pairs.add(MemberValuePair("headers", headers))
        if (type != HttpAction.Type.SIMPLE)
            pairs.add(MemberValuePair("type", NameExpr("HttpAction.Type.".plus(type.name))))
        if (method != HttpAction.Method.GET)
            pairs.add(MemberValuePair("method", NameExpr("HttpAction.Method.".plus(method.name))))
        annotation = NormalAnnotationExpr(actionName, pairs)
    } else {
        annotation = SingleMemberAnnotationExpr(actionName, path)
    }
    classDeclaration.annotations = listOf(annotation)
    return classDeclaration
}

fun CompilationUnit.save(path: java.nio.file.Path) {
    var file = path.toFile()
    if (file.exists() || file.mkdirs()) {
        file = File(file, this.types.first().name.plus(".java"))
        with(FileWriter(file)) {
            write(this@save.toString())
            flush()
            close()
        }
        println("File $file saved")
    } else {
        error("Folder $file couldn't be made")
    }
}

inline fun Parameter.toJanetField(action: (imports: MutableList<ImportDeclaration>) -> Unit): FieldDeclaration {
    val imports = ArrayList<ImportDeclaration>()
    if (type.toString().containsOne("TypedFile", File::class.simpleName!!)) {
        type = ClassOrInterfaceType(FileBody::class.simpleName)
        imports.add(ImportDeclaration(ASTHelper.createNameExpr(FileBody::class.qualifiedName), false, false))
    }

    if (type.toString().contains(RequestBody::class.simpleName!!)) {
        type = ClassOrInterfaceType(BytesArrayBody::class.simpleName)
        imports.add(ImportDeclaration(ASTHelper.createNameExpr(BytesArrayBody::class.qualifiedName), false, false))
    }
    val field = FieldDeclaration(0, type, VariableDeclarator(id))
    annotations.forEach {
        when (it.name.name) {
            retrofit2.http.Field::class.simpleName -> {
                field.annotations.add(SingleMemberAnnotationExpr(NameExpr(Field::class.simpleName),
                        it.getValue("value")))
                imports.add(ImportDeclaration(ASTHelper.createNameExpr(Field::class.qualifiedName), false, false))
            }
            retrofit2.http.FieldMap::class.simpleName -> {
                field.annotations.add(MarkerAnnotationExpr(NameExpr("FieldMap!!!//TODO")))
            }
            retrofit2.http.Body::class.simpleName -> {
                field.annotations.add(MarkerAnnotationExpr(NameExpr(Body::class.simpleName)))
                imports.add(ImportDeclaration(ASTHelper.createNameExpr(Body::class.qualifiedName), false, false))
            }
            retrofit2.http.Header::class.simpleName -> {
                field.annotations.add(SingleMemberAnnotationExpr(NameExpr(RequestHeader::class.simpleName),
                        it.getValue("value")))
                imports.add(ImportDeclaration(ASTHelper.createNameExpr(RequestHeader::class.qualifiedName), false, false))
            }
            retrofit2.http.Headers::class.simpleName -> {
                field.annotations.add(MarkerAnnotationExpr(NameExpr("Headers!!!//TODO")))
            }
            retrofit2.http.Part::class.simpleName -> {
                val annotation: AnnotationExpr
                val name = NameExpr(Part::class.simpleName)
                val value = it.getValue("value")
                val encoding = it.getValue("encoding")
                if (encoding != null) {
                    annotation = NormalAnnotationExpr(name,
                            listOf(MemberValuePair("value", value), MemberValuePair("encoding", encoding)))
                } else {
                    annotation = SingleMemberAnnotationExpr(name, value)
                }
                field.annotations.add(annotation)
                imports.add(ImportDeclaration(ASTHelper.createNameExpr(Part::class.qualifiedName), false, false))
            }
            retrofit2.http.PartMap::class.simpleName -> {
                field.annotations.add(MarkerAnnotationExpr(NameExpr("PartMap!!!//TODO")))
            }
            retrofit2.http.Path::class.simpleName -> {
                val annotation: AnnotationExpr
                val name = NameExpr(Path::class.simpleName)
                val value = it.getValue("value")
                val encoded = it.getValue("encoded")
                if (encoded != null) {
                    annotation = NormalAnnotationExpr(name,
                            listOf(MemberValuePair("value", value), MemberValuePair("encoded", encoded)))
                } else {
                    annotation = SingleMemberAnnotationExpr(name, value)
                }
                field.annotations.add(annotation)
                imports.add(ImportDeclaration(ASTHelper.createNameExpr(Path::class.qualifiedName), false, false))
            }
            retrofit2.http.Query::class.simpleName -> {
                val annotation: AnnotationExpr
                val name = NameExpr(Query::class.simpleName)
                val value = it.getValue("value")
                val encoded = it.getValue("encoded")
                if (encoded != null) {
                    annotation = NormalAnnotationExpr(name,
                            listOf(MemberValuePair("value", value), MemberValuePair("encodeName", encoded)))
                } else {
                    annotation = SingleMemberAnnotationExpr(name, value)
                }
                field.annotations.add(annotation)
                imports.add(ImportDeclaration(ASTHelper.createNameExpr(Query::class.qualifiedName), false, false))
            }
            retrofit2.http.QueryMap::class.simpleName -> {
                field.annotations.add(MarkerAnnotationExpr(NameExpr("QueryMap!!!//TODO")))
            }
            retrofit2.http.Url::class.simpleName -> {
                field.annotations.add(MarkerAnnotationExpr(NameExpr("Url!!!//TODO")))
            }
        }
    }
    action.invoke(imports)
    return field
}

fun String.containsOne(vararg elements: String): Boolean {
    for (element in elements) {
        if (this.contains(element)) {
            return true
        }
    }
    return false
}

fun AnnotationExpr.getValue(name: String): Expression? {
    if (this is SingleMemberAnnotationExpr
            && "value".equals(name)) {
        return memberValue
    }
    if (this is NormalAnnotationExpr
            && pairs != null) {
        return pairs
                .filter { it.name.equals(name) }
                .map { it.value }
                .firstOrNull()
    }
    return null
}
