

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;


public class compiler {
	public static void main(String[] args) throws Exception {

		String filepath;
		String flag = ""; // either -l or -p for now

		if(args.length == 0)
			throw new Exception("Too few arguments");
		else if(args.length == 1)
			filepath = args[0];
		else if(args.length == 2) {
			flag = args[0];
			filepath = args[1];
		}
		else
			throw new Exception("Too many arguments");

		String jpl_code = getFileContents(filepath);


		// resolve flags
		if(flag.equals("-l")) {
			var output = Lexer.Lex(jpl_code);
			for(var token : output) {
				System.out.println(token.toString());
			}
		}
		if(flag.equals("-p") || true) { // TODO remove true, temp for autograder
			var output = Parser.parse_code( Lexer.Lex(jpl_code) );
			for(var node : output) {
				System.out.println(node.toString());
			}
		}

/*
		String jpl_code = "";
		var output = Parser.parse_code( Lexer.Lex(jpl_code) );
		for(var node : output) {
			System.out.println(node.toString());
		}*/



		System.out.println("Compilation succeeded");
	}

	public static String getFileContents(String filepath) throws Exception {
		//String currentWorkingDirectory = System.getProperty("user.dir");
		Path fullPath = Paths.get(filepath);

		String jpl_code = "";
		try {
			jpl_code = Files.readString(fullPath);

			// Print the content
			//System.out.println(jpl_code);
		} catch (IOException e) {
			e.printStackTrace();
			throw new Exception("Compilation failed");
		}
		return jpl_code;
	}

}

class Parser {

	public static List<ASTNode> parse_code(List<Lexer.Token> tokens) throws ParserException {
		try {
			List<ASTNode> list = new ArrayList<>();

			int pos = 0;
			// needs first newline as all subsequent commands must end in newline
			if(!is_token(tokens, pos, Lexer.NewlineToken.class)) {
				tokens.add(0, new Lexer.NewlineToken());
			}


			while(pos < tokens.size()) {
				if(!is_token(tokens, pos, Lexer.NewlineToken.class))
					throw new ParserException("Cmd not terminated by newline at: " + tokens.get(pos-1).start_byte);
				pos++;
				if(is_token(tokens, pos, Lexer.EndOfFileToken.class))
					return list;

				ASTNode node = parse_cmd(tokens, pos); // jpl is a sequence of commands
				pos = node.end_pos;
				list.add(node);
			}

			return list;
		} catch (Exception e) {
			System.out.println("Compilation failed\n");
			throw new ParserException("Caught Exception: " + e.toString());
		}
	}



	public static class ASTNode {
		public int start_byte; // byte in jpl code for debugging
		public int end_pos; // end position in list of tokens, important second-return
	}

	private static class Cmd extends ASTNode {}
	private static class Expr extends ASTNode {}
	private static class Type extends ASTNode {}
	private static class Stmt extends ASTNode {}
	private static class LValue extends ASTNode {
		String identifier;
	}
	private static class Binding extends ASTNode{
		LValue lvalue;
		Type type;

		public Binding(int start_byte, int end_pos, LValue lvalue, Type type) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.lvalue = lvalue;
			this.type = type;
		}

		@Override
		public String toString() {
			return lvalue.toString() + " " + type.toString();
		}
	}

	private static class VarLValue extends LValue {
		public VarLValue(int start_byte, int end_pos, String identifier) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.identifier = identifier;
		}

		@Override
		public String toString() {
			return "(VarLValue " + identifier + ")";
		}
	}
	private static class ArrayLValue extends LValue {
		List<String> variables;

		public ArrayLValue(int start_byte, int end_pos, String identifier, List<String> variables) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.identifier = identifier;
			this.variables = variables;
		}

		@Override
		public String toString() {
			String txt = "";
			for(String v : variables)
				txt += " " + v;

			return "(ArrayLValue " + identifier + txt + ")";
		}
	}

	private static class LetStmt extends Stmt {
		LValue lvalue;
		Expr expr;

		public LetStmt(int start_byte, int end_pos, LValue lvalue, Expr expr) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.lvalue = lvalue;
			this.expr = expr;
		}

		@Override
		public String toString() {
			return "(LetStmt " + lvalue.toString() + " " + expr.toString() + ")";
		}
	}
	private static class AssertStmt extends Stmt {
		Expr expr;
		String str;

		public AssertStmt(int start_byte, int end_pos, Expr expr, String str) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.expr = expr;
			this.str = str;
		}

		@Override
		public String toString() {
			return "(AssertStmt " + expr.toString() + " " + str + ")";
		}
	}
	private static class ReturnStmt extends Stmt {
		Expr expr;

		public ReturnStmt(int start_byte, int end_pos, Expr expr) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.expr = expr;
		}

		@Override
		public String toString() {
			return "(ReturnStmt " + expr.toString() + ")";
		}
	}

	private static class ReadCmd extends Cmd {
		String read_file;
		LValue lvalue;

		public ReadCmd(int start_byte, int end_pos, String read_file, LValue lvalue) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.read_file = read_file;
			this.lvalue = lvalue;
		}

		@Override
		public String toString() {
			return "(ReadCmd " + read_file + " " + lvalue.toString() + ")";
		}
	}
	private static class WriteCmd extends Cmd {
		Expr expr;
		String write_file;

		public WriteCmd(int start_byte, int end_pos, Expr expr, String write_file) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.expr = expr;
			this.write_file = write_file;
		}

		@Override
		public String toString() {
			return "(WriteCmd " + expr.toString() + " " + write_file + ")";
		}
	}
	private static class LetCmd extends Cmd {
		LValue lvalue;
		Expr expr;

		public LetCmd(int start_byte, int end_pos, LValue lvalue,  Expr expr) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.lvalue = lvalue;
			this.expr = expr;
		}

		@Override
		public String toString() {
			return "(LetCmd " + lvalue.toString() + " " + expr.toString() + ")";
		}
	}
	private static class AssertCmd extends Cmd {
		Expr expr;
		String str;

		public AssertCmd(int start_byte, int end_pos, Expr expr, String str) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.expr = expr;
			this.str = str;
		}

		@Override
		public String toString() {
			return "(AssertCmd " + expr.toString() + " " + str + ")";
		}
	}
	private static class PrintCmd extends Cmd {
		String str;

		public PrintCmd(int start_byte, int end_pos, String str) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.str = str;
		}

		@Override
		public String toString() {
			return "(PrintCmd " + str + ")";
		}
	}
	private static class ShowCmd extends Cmd {
		Expr expr;

		public ShowCmd(int start_byte, int end_pos, Expr expr) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.expr = expr;
		}

		@Override
		public String toString() {
			return "(ShowCmd " + expr.toString() + ")";
		}
	}
	private static class TimeCmd extends Cmd {
		Cmd cmd;

		public TimeCmd(int start_byte, int end_pos, Cmd cmd) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.cmd = cmd;
		}

		@Override
		public String toString() {
			return "(TimeCmd " + cmd.toString() + ")";
		}
	}
	private static class FnCmd extends Cmd {
		String identifier;
		List<Binding> bindings;
		Type return_value;
		List<Stmt> statements;

		public FnCmd(int start_byte, int end_pos, String identifier, List<Binding> bindings, Type return_value, List<Stmt> statements ) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.identifier = identifier;
			this.bindings = bindings;
			this.return_value = return_value;
			this.statements = statements;
		}

		@Override
		public String toString() {
			String bindings_txt = "";
			if(bindings != null && bindings.size() != 0) {
				for(Binding bind : bindings)
					bindings_txt += bind.toString() + " ";
				bindings_txt = bindings_txt.substring(0, bindings_txt.length() - 1); // remove final space
			}

			String statements_txt = "";
			if(statements != null && statements.size() != 0) {
				for(Stmt s : statements)
					statements_txt += " " + s.toString();
			}

			return "(FnCmd " + identifier + " ((" + bindings_txt + ")) " + return_value + statements_txt + ")";
		}
	}
	private static class StructCmd extends Cmd {
		String identifier;
		List<String> variables;
		List<Type> types;

		public StructCmd(int start_byte, int end_pos, String identifier, List<String> variables, List<Type> types) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.identifier = identifier;
			this.variables = variables;
			this.types = types;
		}

		@Override
		public String toString() {
			String bindings_txt = "";
			if(variables == null || variables.size() == 0) {
				return "(StructCmd " + identifier + ")";
			}
			else {
				for(int i = 0; i < variables.size(); i++)
					bindings_txt += " " + variables.get(i) + " " + types.get(i).toString();
			}

			return "(StructCmd " + identifier + bindings_txt + ")";
		}
	}

	private static class IntExpr extends Expr {
		long integer;

		public IntExpr(int start_byte, int end_pos, long integer) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.integer = integer;
		}

		@Override
		public String toString() {
			return "(IntExpr " + integer + ")";
		}
	}
	private static class FloatExpr extends Expr {
		double float_val;

		public FloatExpr(int start_byte, int end_pos, double float_val) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.float_val = float_val;
		}

		@Override
		public String toString() {
			return "(FloatExpr " + (long)float_val + ")";
		}
	}
	private static class TrueExpr extends Expr {
		public TrueExpr(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(TrueExpr)";
		}
	}
	private static class FalseExpr extends Expr {
		public FalseExpr(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(FalseExpr)";
		}
	}
	private static class VarExpr extends Expr {
		String str;

		public VarExpr(int start_byte, int end_pos, String str) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.str = str;
		}

		@Override
		public String toString() {
			return "(VarExpr " + str + ")";
		}
	}
	private static class ArrayLiteralExpr extends Expr {
		List<Expr> exprs;

		public ArrayLiteralExpr(int start_byte, int end_pos, List<Expr> exprs) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.exprs = exprs;
		}

		@Override
		public String toString() {
			if(exprs == null || exprs.size() == 0)
				return "(ArrayLiteralExpr)";
			else {
				String out = "";
				for(Expr ex : exprs)
					out += ex.toString() + " ";

				return "(ArrayLiteralExpr " + out.substring(0, out.length()-1) + ")";
			}
		}
	}
	private static class VoidExpr extends Expr {
		public VoidExpr(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(VoidExpr)";
		}
	}
	private static class StructLiteralExpr extends Expr {
		String identifier;
		List<Expr> expressions;

		public StructLiteralExpr(int start_byte, int end_pos, String identifier, List<Expr> expressions) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.identifier = identifier;
			this.expressions = expressions;
		}

		@Override
		public String toString() {
			if(expressions == null || expressions.size() == 0)
				return "(StructLiteralExpr " + identifier + ")";
			else {
				String str = "";
				for(Expr e : expressions)
					str += " " + e.toString();

				return "(StructLiteralExpr " + identifier + str + ")";
			}
		}
	}
	private static class DotExpr extends Expr {
		Expr expr;
		String str;

		public DotExpr(int start_byte, int end_pos, Expr expr, String str) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.expr = expr;
			this.str = str;
		}

		@Override
		public String toString() {
			return "(DotExpr " + expr.toString() + " " + str + ")";
		}
	}
	private static class ArrayIndexExpr extends Expr {
		Expr expr;
		List<Expr> expressions;

		public ArrayIndexExpr(int start_byte, int end_pos, Expr expr, List<Expr> expressions) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.expr = expr;
			this.expressions = expressions;
		}

		@Override
		public String toString() {
			if(expressions == null || expressions.size() == 0)
				return "(ArrayIndexExpr " + expr.toString() + ")";
			else {
				String str = "";
				for(Expr e : expressions)
					str += " " + e.toString();

				return "(ArrayIndexExpr " + expr.toString() + str + ")";
			}
		}
	}
	private static class CallExpr extends Expr {
		String identifier;
		List<Expr> expressions;

		public CallExpr(int start_byte, int end_pos, String identifier, List<Expr> expressions) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.identifier = identifier;
			this.expressions = expressions;
		}

		@Override
		public String toString() {
			if(expressions == null || expressions.size() == 0)
				return "(CallExpr " + identifier + ")";
			else {
				String str = "";
				for(Expr e : expressions)
					str += " " + e.toString();

				return "(CallExpr " + identifier + str + ")";
			}
		}
	}

	private static class IntType extends Type {

		public IntType(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(IntType)";
		}
	}
	private static class FloatType extends Type {

		public FloatType(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(FloatType)";
		}
	}
	private static class BoolType extends Type {

		public BoolType(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(BoolType)";
		}
	}
	private static class VoidType extends Type {

		public VoidType(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(VoidType)";
		}
	}
	private static class StructType extends Type {
		String struct_name;

		public StructType(int start_byte, int end_pos, String struct_name) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.struct_name = struct_name;
		}

		@Override
		public String toString() {
			return "(StructType " + struct_name + ")";
		}
	}
	private static class ArrayType extends Type {
		Type type;
		int dimension;

		public ArrayType(int start_byte, int end_pos, Type type, int dimension) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.type = type;
			this.dimension = dimension;
		}

		@Override
		public String toString() {
			return "(ArrayType " + type.toString() + " " + dimension + ")";
		}
	}


	private static class ParserException extends Exception {

		private String msg = "Compilation failed\n";

		public ParserException(String msg) {
			this.msg += "Parsing error: " + msg;
		}

		@Override
		public String toString() {
			return msg;
		}
	}



	// input is String.class    Integer.class etc.
	// should return contents of token
	private static String expect_token(List<Lexer.Token> tokens, int pos, Class<?> type) throws ParserException {
		var token = tokens.get(pos);
		if(!type.isInstance(token))
			throw new ParserException("\nExpected " + type.getSimpleName() + " got " + token.getClass().getSimpleName()
					+ "\nByte position: " + token.start_byte);

		if(type == Lexer.KeywordToken.class)
			return ((Lexer.KeywordToken)token).keyword.toLowerCase();
		else if(type == Lexer.StringToken.class)
			return ((Lexer.StringToken)token).text;
		else if(type == Lexer.IdentifierToken.class)
			return ((Lexer.IdentifierToken)token).identifier;
		else if(type == Lexer.PunctuationToken.class)
			return ((Lexer.PunctuationToken)token).character + "";
		else if(type == Lexer.FloatToken.class)
			return ((Lexer.FloatToken)token).literal_val;
		else if(type == Lexer.IntToken.class)
			return ((Lexer.IntToken)token).literal_val;

		return null; //TODO remove
	}

	private static boolean is_token(List<Lexer.Token> tokens, int pos, Class<?> type) {
		var token = tokens.get(pos);
		return type.isInstance(token);
	}

	private static boolean is_token(List<Lexer.Token> tokens, int pos, Class<?> type, String value) {
		var token = tokens.get(pos);
		if(!type.isInstance(token))
			return false;

		if(type == Lexer.KeywordToken.class)
			return ((Lexer.KeywordToken)token).keyword.toLowerCase().equals(value.toLowerCase());
		else if(type == Lexer.PunctuationToken.class)
			return (((Lexer.PunctuationToken)token).character + "").equals(value);

		return type.isInstance(token);
	}

	private static void expect_keyword(List<Lexer.Token> tokens, int pos, String expected) throws ParserException {
		String keyword = expect_token(tokens, pos, Lexer.KeywordToken.class);
		if(!keyword.equals(expected))
			throw new ParserException("\nExpected keyword: " + expected + " got: " + keyword
					+ "\nByte position: " + tokens.get(pos).start_byte);
	}

	private static void expect_punctuation(List<Lexer.Token> tokens, int pos, char expected) throws ParserException {
		String punctuation = expect_token(tokens, pos, Lexer.PunctuationToken.class);
		if(!punctuation.equals(expected + ""))
			throw new ParserException("\nExpected Punctuation: " + expected + " got: " + punctuation
					+ "\nByte position: " + tokens.get(pos).start_byte);
	}


	/**
	 *  COMMANDS
	 */

	private static Cmd parse_cmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		if(is_token(tokens, pos, Lexer.KeywordToken.class, "read"))
			return parse_readcmd(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "struct"))
			return parse_structcmd(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "fn"))
			return parse_fncmd(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "write"))
			return parse_writecmd(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "let"))
			return parse_letcmd(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "assert"))
			return parse_assertcmd(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "print"))
			return parse_printcmd(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "show"))
			return parse_showcmd(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "time"))
			return parse_timecmd(tokens, pos);

		throw new ParserException("Expected cmd at byte:" + tokens.get(pos).start_byte);
	}

	private static FnCmd parse_fncmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "fn");
		String function_name = expect_token(tokens, pos++, Lexer.IdentifierToken.class);
		expect_punctuation(tokens, pos++, '(');
		List<Binding> bindings = parse_binding_chain(tokens, pos);
		pos = bindings.size() > 0 ?
				bindings.get(bindings.size() - 1).end_pos : pos;
		pos++;
		expect_punctuation(tokens, pos++, ':');
		Type type = parse_type(tokens, pos);
		pos = type.end_pos;
		expect_punctuation(tokens, pos++, '{');
		expect_token(tokens, pos++, Lexer.NewlineToken.class);
		List<Stmt> statements = parse_stmt_chain(tokens, pos);
		pos = statements.size() > 0 ?
				statements.get(statements.size() - 1).end_pos + 2: pos + 1;
		return new FnCmd(tokens.get(start_pos).start_byte, pos, function_name, bindings, type, statements);
	}

	private static StructCmd parse_structcmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "struct");
		String struct_name = expect_token(tokens, pos++, Lexer.IdentifierToken.class);
		expect_punctuation(tokens, pos++, '{');
		expect_token(tokens, pos++, Lexer.NewlineToken.class);

		List<String> variables = new ArrayList<>();
		List<Type> types = new ArrayList<>();

		while(pos < tokens.size()) {
			if(is_token(tokens, pos, Lexer.PunctuationToken.class, "}"))
				return new StructCmd(tokens.get(start_pos).start_byte, ++pos, struct_name, variables, types);

			String var = expect_token(tokens, pos++, Lexer.IdentifierToken.class);
			expect_punctuation(tokens, pos++, ':');
			Type type = parse_type(tokens, pos);
			pos = type.end_pos;

			variables.add(var);
			types.add(type);

			expect_token(tokens, pos++, Lexer.NewlineToken.class);
		}

		throw new ParserException("Expected closing curly brace at byte:" + tokens.get(pos).start_byte);
	}

	private static ReadCmd parse_readcmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "read");
		expect_keyword(tokens, pos++, "image");
		String str = expect_token(tokens, pos++, Lexer.StringToken.class);
		expect_keyword(tokens, pos++, "to");
		LValue lvalue = parse_lvalue(tokens, pos);
		pos = lvalue.end_pos;

		return new ReadCmd(tokens.get(start_pos).start_byte, pos, str, lvalue);
	}

	private static WriteCmd parse_writecmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "write");
		expect_keyword(tokens, pos++, "image");
		Expr expr_node = parse_expr(tokens, pos);
		pos = expr_node.end_pos;
		expect_keyword(tokens, pos++, "to");
		String str = expect_token(tokens, pos++, Lexer.StringToken.class);

		return new WriteCmd(tokens.get(start_pos).start_byte, pos, expr_node, str);
	}

	private static LetCmd parse_letcmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "let");
		LValue lvalue = parse_lvalue(tokens, pos);
		pos = lvalue.end_pos;
		expect_punctuation(tokens, pos++, '=');
		Expr expr = parse_expr(tokens, pos);
		pos = expr.end_pos;

		return new LetCmd(tokens.get(start_pos).start_byte, pos, lvalue, expr);
	}

	private static AssertCmd parse_assertcmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "assert");
		Expr expr = parse_expr(tokens, pos);
		pos = expr.end_pos;
		expect_punctuation(tokens, pos++, ',');
		String str = expect_token(tokens, pos++, Lexer.StringToken.class);

		return new AssertCmd(tokens.get(start_pos).start_byte, pos, expr, str);
	}

	private static PrintCmd parse_printcmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "print");
		String str = expect_token(tokens, pos++, Lexer.StringToken.class);

		return new PrintCmd(tokens.get(start_pos).start_byte, pos, str);
	}

	private static ShowCmd parse_showcmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "show");
		Expr expr = parse_expr(tokens, pos);
		pos = expr.end_pos;

		return new ShowCmd(tokens.get(start_pos).start_byte, pos, expr);
	}

	private static TimeCmd parse_timecmd(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "time");
		Cmd cmd = parse_cmd(tokens, pos);
		pos = cmd.end_pos;

		return new TimeCmd(tokens.get(start_pos).start_byte, pos, cmd);
	}

	/**
	 *  STATEMENTS
	 */

	private static Stmt parse_stmt(List<Lexer.Token> tokens, int pos) throws ParserException {
		if(is_token(tokens, pos, Lexer.KeywordToken.class, "let"))
			return parse_letstmt(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "assert"))
			return parse_assertstmt(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "return"))
			return parse_returnstmt(tokens, pos);
		else throw new ParserException("Expected stmt at byte:" + tokens.get(pos).start_byte);
	}

	private static LetStmt parse_letstmt(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "let");
		LValue lvalue = parse_lvalue(tokens, pos);
		pos = lvalue.end_pos;
		expect_punctuation(tokens, pos++, '=');
		Expr expr = parse_expr(tokens, pos);
		pos = expr.end_pos;
		return new LetStmt(tokens.get(start_pos).start_byte, pos, lvalue, expr);
	}

	private static AssertStmt parse_assertstmt(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "assert");
		Expr expr = parse_expr(tokens, pos);
		pos = expr.end_pos;
		expect_punctuation(tokens, pos++, ',');
		String str = expect_token(tokens, pos++, Lexer.StringToken.class);
		return new AssertStmt(tokens.get(start_pos).start_byte, pos, expr, str);
	}

	private static ReturnStmt parse_returnstmt(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "return");
		Expr expr = parse_expr(tokens, pos);
		pos = expr.end_pos;
		return new ReturnStmt(tokens.get(start_pos).start_byte, pos, expr);
	}

	private static List<Stmt> parse_stmt_chain(List<Lexer.Token> tokens, int pos) throws ParserException {
		List<Stmt> statements = new ArrayList<>();

		if(is_token(tokens, pos, Lexer.PunctuationToken.class, "}"))
			return statements;

		while(pos < tokens.size()) {
			Stmt stmt = parse_stmt(tokens, pos);
			statements.add(stmt);
			pos = stmt.end_pos;

			if(!is_token(tokens, pos++, Lexer.NewlineToken.class))
				throw new ParserException("Expected newline at byte:" + tokens.get(pos).start_byte);

			if(is_token(tokens, pos, Lexer.PunctuationToken.class, "}"))
				return statements;
		}

		throw new ParserException("Expected closing curly brace inside function definition");
	}


	/**
	 *  EXPRESSIONS
	 */

	private static Expr parse_expr(List<Lexer.Token> tokens, int pos) throws ParserException {
		Expr expr;

		if(is_token(tokens, pos, Lexer.IntToken.class))
			expr = parse_intexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.FloatToken.class))
			expr = parse_floatexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "true"))
			expr = parse_trueexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "false"))
			expr = parse_falseexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "void"))
			expr = parse_voidexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.IdentifierToken.class))
			expr = parse_varexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.PunctuationToken.class, "["))
			expr = parse_arrayliteralexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.PunctuationToken.class, "("))
			expr = parse_innerexpr(tokens, pos);
		else throw new ParserException("Expected expr at byte:" + tokens.get(pos).start_byte);

		return parse_expr_cont(tokens, expr.end_pos, expr);

	}

	private static Expr parse_expr_cont(List<Lexer.Token> tokens, int pos, Expr expr_orig) throws ParserException {
		// <exp>.<variable>
		if(is_token(tokens, pos, Lexer.PunctuationToken.class, ".")) {
			pos++;
			String identifier = expect_token(tokens, pos++, Lexer.IdentifierToken.class);
			Expr new_orig = new DotExpr(expr_orig.start_byte, pos, expr_orig, identifier);
			return parse_expr_cont(tokens, pos, new_orig);
		}
		// <expr> [<expr, ...]
		else if(is_token(tokens, pos, Lexer.PunctuationToken.class, "[")) {
			pos++;
			List<Expr> expressions = parse_expr_chain(tokens, pos, "]");
			int end_pos = expressions.size() > 0 ?
					expressions.get(expressions.size() - 1).end_pos : pos;
			end_pos++;
			Expr new_orig = new ArrayIndexExpr(expr_orig.start_byte, end_pos, expr_orig, expressions);
			return parse_expr_cont(tokens, end_pos, new_orig);
		}
		else return expr_orig;
	}

	private static List<Expr> parse_expr_chain(List<Lexer.Token> tokens, int pos, String closing_delimiter) throws ParserException {
		// parses  <expr>, ... )  with any delimiter

		List<Expr> expressions = new ArrayList<>();

		// i.e. [] {} ()
		if(is_token(tokens, pos, Lexer.PunctuationToken.class, closing_delimiter))
			return expressions;

		while(pos < tokens.size()) {
			Expr expr = parse_expr(tokens, pos);
			expressions.add(expr);
			pos = expr.end_pos;

			if(is_token(tokens, pos, Lexer.PunctuationToken.class, closing_delimiter))
				return expressions;

			if(!is_token(tokens, pos, Lexer.PunctuationToken.class, ","))
				throw new ParserException("Expected comma at byte:" + tokens.get(pos).start_byte);

			pos++;
		}

		throw new ParserException("Expected closing delimiter");
	}

	private static Expr parse_innerexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		expect_punctuation(tokens, pos++, '(');
		Expr expr = parse_expr(tokens, pos);
		pos = expr.end_pos;
		expect_punctuation(tokens, pos++, ')');
		expr.end_pos = pos;
		return expr;
	}

	private static IntExpr parse_intexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		String literal_val = expect_token(tokens, pos++, Lexer.IntToken.class);
		long val = Long.parseLong(literal_val);

		return new IntExpr(tokens.get(pos-1).start_byte, pos, val);
	}

	private static FloatExpr parse_floatexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		String literal_val = expect_token(tokens, pos++, Lexer.FloatToken.class);
		double val = Double.parseDouble(literal_val);
		if(val == Double.POSITIVE_INFINITY || val == Double.NEGATIVE_INFINITY)
			throw new ParserException("Float is too big");

		return new FloatExpr(tokens.get(pos-1).start_byte, pos, val);
	}

	private static VoidExpr parse_voidexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		expect_keyword(tokens, pos++, "void");
		return new VoidExpr(tokens.get(pos-1).start_byte, pos);
	}

	private static TrueExpr parse_trueexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		expect_keyword(tokens, pos++, "true");
		return new TrueExpr(tokens.get(pos-1).start_byte, pos);
	}

	private static FalseExpr parse_falseexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		expect_keyword(tokens, pos++, "false");
		return new FalseExpr(tokens.get(pos-1).start_byte, pos);
	}

	private static Expr parse_varexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos-1;
		String identifier = expect_token(tokens, pos++, Lexer.IdentifierToken.class);

		// variable {<expr>,...}
		if(is_token(tokens, pos, Lexer.PunctuationToken.class, "{")) {
			pos++;
			List<Expr> expressions = parse_expr_chain(tokens, pos, "}");
			int end_pos = expressions.size() > 0 ?
					expressions.get(expressions.size() - 1).end_pos : pos;
			end_pos++;
			return new StructLiteralExpr(tokens.get(start_pos).start_byte, end_pos, identifier, expressions);
		}
		// variable (<expr>,...)
		else if(is_token(tokens, pos, Lexer.PunctuationToken.class, "(")) {
			pos++;
			List<Expr> expressions = parse_expr_chain(tokens, pos, ")");
			int end_pos = expressions.size() > 0 ?
					expressions.get(expressions.size() - 1).end_pos : pos;
			end_pos++;
			return new CallExpr(tokens.get(start_pos).start_byte, end_pos, identifier, expressions);
		}
		else return new VarExpr(tokens.get(start_pos).start_byte, pos, identifier);

	}

	private static ArrayLiteralExpr parse_arrayliteralexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_punctuation(tokens, pos++, '[');
		List<Expr> expressions = parse_expr_chain(tokens, pos, "]");

		int end_pos = expressions.size() > 0 ?
				expressions.get(expressions.size() - 1).end_pos : pos;
		end_pos++;

		return new ArrayLiteralExpr(tokens.get(start_pos).start_byte, end_pos, expressions);
	}


	/**
	 *  TYPES
	 */

	private static Type parse_type(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		Type type;

		if(is_token(tokens, pos, Lexer.KeywordToken.class, "int"))
			type = new IntType(tokens.get(start_pos).start_byte, ++pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "float"))
			type = new FloatType(tokens.get(start_pos).start_byte, ++pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "bool"))
			type = new BoolType(tokens.get(start_pos).start_byte, ++pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "void"))
			type = new VoidType(tokens.get(start_pos).start_byte, ++pos);
		else if(is_token(tokens, pos, Lexer.IdentifierToken.class)) {
			String identifier = expect_token(tokens, pos, Lexer.IdentifierToken.class);
			type = new StructType(tokens.get(start_pos).start_byte, ++pos, identifier);
		}
		else throw new ParserException("Expected type at byte:" + tokens.get(start_pos).start_byte);

		return parse_type_cont(tokens, pos, type);
	}

	private static Type parse_type_cont(List<Lexer.Token> tokens, int pos, Type type_orig) throws ParserException {
		int start_pos = pos;

		if(is_token(tokens, pos++, Lexer.PunctuationToken.class, "[")) {
			int dimension = 1;
			while(is_token(tokens, pos++, Lexer.PunctuationToken.class, ","))
				dimension++;

			if(is_token(tokens, pos-1, Lexer.PunctuationToken.class, "]")) {
				Type array_type = new ArrayType(tokens.get(start_pos).start_byte, pos, type_orig, dimension);
				return parse_type_cont(tokens, pos, array_type);
			}

			throw new ParserException("Unclosed bracket starting at byte:" + tokens.get(start_pos).start_byte);
		}
		else return type_orig;
	}

	/**
	 *  LVALUE
	 */

	private static LValue parse_lvalue(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		String identifier = expect_token(tokens, pos++, Lexer.IdentifierToken.class);
		if(is_token(tokens, pos, Lexer.PunctuationToken.class, "[")) {
			pos++;
			List<String> variables = parse_variable_chain(tokens, pos);
			int end_pos = variables.size() == 0 ? pos + 1 : pos + (variables.size() * 2);
			return new ArrayLValue(tokens.get(start_pos).start_byte, end_pos, identifier, variables);
		}
		else return new VarLValue(tokens.get(start_pos).start_byte, pos, identifier);
	}

	private static List<String> parse_variable_chain(List<Lexer.Token> tokens, int pos) throws ParserException {
		List<String> variables = new ArrayList<>();

		if(is_token(tokens, pos, Lexer.PunctuationToken.class, "]"))
			return variables;

		while(pos < tokens.size()) {
			String var = expect_token(tokens, pos++, Lexer.IdentifierToken.class);
			variables.add(var);

			if(is_token(tokens, pos, Lexer.PunctuationToken.class, "]"))
				return variables;

			if(!is_token(tokens, pos, Lexer.PunctuationToken.class, ","))
				throw new ParserException("Expected comma at byte:" + tokens.get(pos).start_byte);

			pos++;
		}

		throw new ParserException("Expected closing bracket");
	}


	/**
	 *  BINDINGS
	 */

	private static Binding parse_binding(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		LValue lvalue = parse_lvalue(tokens, pos);
		pos = lvalue.end_pos;
		expect_punctuation(tokens, pos++, ':');
		Type type = parse_type(tokens, pos);
		pos = type.end_pos;
		return new Binding(tokens.get(pos).start_byte, pos, lvalue, type);
	}

	private static List<Binding> parse_binding_chain(List<Lexer.Token> tokens, int pos) throws ParserException {
		List<Binding> bindings = new ArrayList<>();

		if(is_token(tokens, pos, Lexer.PunctuationToken.class, ")"))
			return bindings;

		while(pos < tokens.size()) {
			Binding bind = parse_binding(tokens, pos);
			bindings.add(bind);
			pos = bind.end_pos;

			if(is_token(tokens, pos, Lexer.PunctuationToken.class, ")"))
				return bindings;

			if(!is_token(tokens, pos, Lexer.PunctuationToken.class, ","))
				throw new ParserException("Expected comma at byte:" + tokens.get(pos).start_byte);

			pos++;
		}

		throw new ParserException("Expected closing parenthesis inside parameter declaration");
	}

}

class Lexer {
	public static List<Token> Lex(String jpl_code) throws LexerException {
		try{
			if(!hasOnlyValidCharacters(jpl_code))
				throw new LexerException("Code contains invalid character(s)");

			List<Token> tokens = new ArrayList<>();
			int curr_pos = 0;
			int single_comment_end_pos;
			int multi_comment_end_pos;

			while(curr_pos < jpl_code.length()) {
				single_comment_end_pos = isSingleLineComment(jpl_code, curr_pos);
				multi_comment_end_pos = isMultiLineComment(jpl_code, curr_pos);

				if(jpl_code.charAt(curr_pos) == ' ') { // check for white space
					curr_pos++;
					continue;
				}
				else if(startsWithSubstring(jpl_code, curr_pos, "\\\n")) { // newline escape \ not actually a newline
					curr_pos += 2;
					continue;
				}
				else if(jpl_code.charAt(curr_pos) == '\n') {   // check for newline
					addNewline(tokens);
					curr_pos++;
					continue;
				}
				else if(single_comment_end_pos != -1) { // check for single line comment
					curr_pos = single_comment_end_pos;
					addNewline(tokens);
					continue;
				}
				else if(multi_comment_end_pos != -1) { // check for multi line comment
					curr_pos = multi_comment_end_pos;
					continue;
				}

				// check for float
				Token t = isFloat(jpl_code, curr_pos);
				if(t != null) {
					tokens.add(t);
					curr_pos = t.end_byte;
					continue;
				}

				// check for int
				t = isInt(jpl_code, curr_pos);
				if(t != null) {
					tokens.add(t);
					curr_pos = t.end_byte;
					continue;
				}

				// check for string
				t = isString(jpl_code, curr_pos);
				if(t != null) {
					tokens.add(t);
					curr_pos = t.end_byte;
					continue;
				}

				// check for operator
				t = isOperator(jpl_code, curr_pos);
				if(t != null) {
					tokens.add(t);
					curr_pos = t.end_byte;
					continue;
				}

				// check for variable
				t = isIdentifier(jpl_code, curr_pos);
				if(t != null) {
					tokens.add(t);
					curr_pos = t.end_byte;
					continue;
				}

				// check for keyword
				t = isKeyword(jpl_code, curr_pos);
				if(t != null) {
					tokens.add(t);
					curr_pos = t.end_byte;
					continue;
				}

				// check for punctuation
				t = isPunctuation(jpl_code, curr_pos);
				if(t != null) {
					tokens.add(t);
					curr_pos = t.end_byte;
					continue;
				}

				throw new LexerException("Unknown token at: " + curr_pos + "\n JPL code: " + jpl_code.substring(curr_pos));
			}

			addNewline(tokens);
			tokens.add(new EndOfFileToken());
			return tokens;
		} catch(Exception e) {
			System.out.println("Compilation failed\n");
			throw new LexerException("Caught Exception: " + e.toString());
		}
	}

	public static class Token {
		public int start_byte; // inclusive
		public int end_byte; // not inclusive
	}

	public static class NewlineToken extends Token {
		@Override
		public String toString() {
			return "NEWLINE";
		}
	}

	public static class EndOfFileToken extends Token {
		@Override
		public String toString() {
			return "END_OF_FILE";
		}
	}

	public static class IntToken extends Token {
		String literal_val;

		public IntToken(String literal_val, int start_byte, int end_byte) {
			this.literal_val = literal_val;
			this.start_byte = start_byte;
			this.end_byte = end_byte;
		}

		@Override
		public String toString() {
			return "INTVAL \'" + literal_val + "\'";
		}
	}

	public static class FloatToken extends Token {
		String literal_val;

		public FloatToken(String literal_val, int start_byte, int end_byte) {
			this.literal_val = literal_val;
			this.start_byte = start_byte;
			this.end_byte = end_byte;
		}

		@Override
		public String toString() {
			return "FLOATVAL \'" + literal_val + "\'";
		}
	}

	// 9 punctuation:  LCURLY LPAREN LSQUARE RCURLY RPAREN RSQUARE DOT COMMA COLON
	// 10th punctuation to make things easy: EQUALS
	public static class PunctuationToken extends Token {
		String name;
		char character;

		public PunctuationToken(String name, char character, int start_byte, int end_byte) {
			this.name = name.toUpperCase();
			this.character = character;
			this.start_byte = start_byte;
			this.end_byte = end_byte;
		}

		@Override
		public String toString() {
			return name + " \'" + character + "\'";
		}
	}

	// 14 operators: + - / * % && || ! > < != == >= <=       (= not an op)
	public static class OperatorToken extends Token {
		String operator;

		public OperatorToken(String operator, int start_byte, int end_byte) {
			this.operator = operator;
			this.start_byte = start_byte;
			this.end_byte = end_byte;
		}

		@Override
		public String toString() {
			return "OP \'" + operator + "\'";
		}
	}

	// 23 keywords
	public static class KeywordToken extends Token {
		String keyword;

		public KeywordToken(String keyword, int start_byte, int end_byte) {
			this.keyword = keyword.toLowerCase();
			this.start_byte = start_byte;
			this.end_byte = end_byte;
		}

		@Override
		public String toString() {
			return keyword.toUpperCase() + " \'" + keyword + "\'";
		}
	}

	public static class StringToken extends Token {
		String text;

		public StringToken(String text, int start_byte, int end_byte) {
			this.text = text;
			this.start_byte = start_byte;
			this.end_byte = end_byte;
		}

		@Override
		public String toString() {
			return "STRING \'" + text + "\'";
		}
	}

	public static class IdentifierToken extends Token {
		String identifier;

		public IdentifierToken(String identifier, int start_byte, int end_byte) {
			this.identifier = identifier;
			this.start_byte = start_byte;
			this.end_byte = end_byte;
		}

		@Override
		public String toString() {
			return "VARIABLE \'" + identifier + "\'";
		}
	}

	// --------------------------------------------------------------------------------------

	private static class LexerException extends Exception {

		private String msg = "Compilation failed\n";

		public LexerException(String msg) {
			this.msg += "Lexing error: " + msg;
		}

		public LexerException(String jpl_code, int start_byte, int end_byte) {
			msg += "Lexing issue starting at byte: " + start_byte + " ending at byte: " + end_byte +
					"\n JPL code here: " + jpl_code.substring(start_byte, end_byte);
		}
		@Override
		public String toString() {
			return msg;
		}

	}

	private static boolean startsWithSubstring(String str, int start_pos, String substring) {
		int str_pos = start_pos;
		int sub_pos = 0;

		while(sub_pos < substring.length()) {
			if(str_pos == str.length() || str.charAt(str_pos) != substring.charAt(sub_pos))
				return false;
			str_pos++;
			sub_pos++;
		}
		return true;
	}

	private static boolean hasOnlyValidCharacters(String jpl_code) {
		// Check if the byte value of the character is valid for JPL
		for(int i = 0; i < jpl_code.length(); i++) {
			char c = jpl_code.charAt(i);
			if((c < 32 || c > 126) && c != 10)
				return false;
		}
		return true;
	}

	private static void addNewline(List<Token> tokens) {
		if(tokens.size() == 0 || !(tokens.get(tokens.size() - 1) instanceof NewlineToken)) // last token was not a newline
			tokens.add(new NewlineToken());
	}

	private static Token isInt(String str, int start_pos) {
		// not an int if it's not a digit to start.
		if(!Character.isDigit(str.charAt(start_pos)))
			return null;

		int curr_pos = start_pos;

		while(curr_pos < str.length()) {
			if(Character.isDigit(str.charAt(curr_pos)))
				curr_pos++;
			else
				break;
		}

		String literal_val = str.substring(start_pos, curr_pos);
		return new IntToken(literal_val, start_pos, curr_pos);
	}

	private static Token isFloat(String str, int start_pos) {
		int curr_pos = start_pos;
		boolean has_digit = false;
		boolean has_dot = false;

		// go through digits before dot
		while(curr_pos < str.length()) {
			if(Character.isDigit(str.charAt(curr_pos)))
				has_digit = true;
			else if(str.charAt(curr_pos) == '.' && !has_dot)
				has_dot = true;
			else
				break;
			curr_pos++;
		}

		if(!has_digit || !has_dot)
			return null;

		String literal_val = str.substring(start_pos, curr_pos);
		return new FloatToken(literal_val, start_pos, curr_pos);
	}

	private static Token isPunctuation(String str, int start_pos) {
		char punctuation = str.charAt(start_pos);

		switch(punctuation) {
			case '[': return new PunctuationToken("LSQUARE", '[', start_pos, start_pos+1);
			case ']': return new PunctuationToken("RSQUARE", ']', start_pos, start_pos+1);
			case '(': return new PunctuationToken("LPAREN", '(', start_pos, start_pos+1);
			case ')': return new PunctuationToken("RPAREN", ')', start_pos, start_pos+1);
			case '{': return new PunctuationToken("LCURLY", '{', start_pos, start_pos+1);
			case '}': return new PunctuationToken("RCURLY", '}', start_pos, start_pos+1);
			case ',': return new PunctuationToken("COMMA", ',', start_pos, start_pos+1);
			case ':': return new PunctuationToken("COLON", ':', start_pos, start_pos+1);
			case '.': return new PunctuationToken("DOT", '.', start_pos, start_pos+1);
			case '=': return new PunctuationToken("EQUALS", '=', start_pos, start_pos+1);
			default: return null;
		}
	}

	private static Token isOperator(String str, int start_pos) {
		// tiered system because != and ! both need to be lexed
		String[] operators_2_bytes = {"&&", "||", "==", "!=", ">=", "<="};
		String[] operators_1_byte = {"+", "-", "/", "*", "%", "!", "<", ">"};

		for(var op : operators_2_bytes) {
			if(startsWithSubstring(str, start_pos, op))
				return new OperatorToken(op, start_pos, start_pos+2);
		}

		for(var op : operators_1_byte) {
			if(startsWithSubstring(str, start_pos, op))
				return new OperatorToken(op, start_pos, start_pos+1);
		}

		return null;
	}

	private static Token isKeyword(String str, int start_pos) {

		String[] keywords = {"array", "assert", "bool", "else", "false", "float", "fn", "if", "image", "int", "let",
				"print", "read", "return", "show", "struct", "sum", "then", "time", "to", "true", "void", "write"};

		for(String keyword : keywords) {
			if(startsWithSubstring(str, start_pos, keyword))
				return new KeywordToken(keyword, start_pos, start_pos+keyword.length());
		}

		return null;
	}

	private static Token isString(String str, int start_pos) throws LexerException {
		if(str.charAt(start_pos) != '\"')
			return null;

		int curr_pos = start_pos+1;
		boolean has_ending_quote = false;

		while(curr_pos < str.length()) {
			if(str.charAt(curr_pos) != '\"')
				curr_pos++;
			else {
				has_ending_quote = true;
				break;
			}
		}

		if(!has_ending_quote) // got to the end of the program with no ending quote
			throw new LexerException(str, start_pos, curr_pos);
		else
			return new StringToken(str.substring(start_pos, curr_pos+1), start_pos, curr_pos+1);
	}

	private static Token isIdentifier(String str, int start_pos) {
		if(!Character.isLetter(str.charAt(start_pos)))
			return null;

		int curr_pos = start_pos;

		while(curr_pos < str.length()) {
			if(Character.isLetterOrDigit(str.charAt(curr_pos)) || str.charAt(curr_pos) == '_')
				curr_pos++;
			else
				break;
		}

		String identifier = str.substring(start_pos, curr_pos);

		// if this string is a keyword or punctuation, not valid
		// bulky because if keyword = 'array' then 'array_var' should be a valid identifier
		KeywordToken kt1 = (KeywordToken)isKeyword(identifier, 0);
		KeywordToken kt2 = (KeywordToken)isPunctuation(identifier, 0);
		if((kt1 != null && kt1.keyword.length() == identifier.length()) ||
				kt2 != null && kt2.keyword.length() == identifier.length())
			return null;

		return new IdentifierToken(identifier, start_pos, curr_pos);
	}

	private static int isSingleLineComment(String str, int start_pos) {
		if(!startsWithSubstring(str, start_pos, "//"))
			return -1;

		int curr_pos = start_pos;
		int max_pos = str.length();

		while(curr_pos < max_pos) {
			if(str.charAt(curr_pos) != '\n')
				curr_pos++;
			else
				break;
		}

		return curr_pos + 1;
	}

	private static int isMultiLineComment(String str, int start_pos) {
		if(!startsWithSubstring(str, start_pos, "/*"))
			return -1;

		int curr_pos = start_pos;
		int max_pos = str.length() - 1;

		while(curr_pos < max_pos) {
			if(str.charAt(curr_pos) == '*' && str.charAt(curr_pos+1) == '/')
				break;
			else
				curr_pos++;
		}

		return curr_pos+2;
	}

}
