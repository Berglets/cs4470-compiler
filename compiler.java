import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;


public class compiler {
	public static void main(String[] args) throws Exception {

		String filepath;
		String flag = "";

		if(args.length == 0)
			throw new Exception("Too few arguments");
		else if(args.length == 1)
			filepath = args[0];
		else if(args.length == 2) {
			flag = args[0];
			filepath = args[1];
		}
		//else
			//throw new Exception("Too many arguments");

		String jpl_code = getFileContents(filepath);


		// resolve flags
		if(flag.equals("-l")) { // lexer
			var output = Lexer.Lex(jpl_code);
			for(var token : output) {
				System.out.println(token.toString());
			}
		}
		if(flag.equals("-p")) { // parser
			var output = Parser.parse_code( Lexer.Lex(jpl_code) );
			for(var node : output) {
				System.out.println(node.toString());
			}
		}
		if(flag.equals("-t")) { // typechecker
			var output = Parser.parse_code( Lexer.Lex(jpl_code) );
			TypeChecker.type_check(output);
			for(var node : output) {
				System.out.println(node.toString());
			}
		}
		if(flag.equals("-i")) {  // c code
			var output = Parser.parse_code( Lexer.Lex(jpl_code) );
			var env = TypeChecker.type_check(output);
			System.out.println(C_Code.convert_to_c(output, env));
		}
		if(flag.equals("-s") || true) { // asm codeTODO remove true, temp for autograder
			var output = Parser.parse_code( Lexer.Lex(jpl_code) );
			var env = TypeChecker.type_check(output);
			var asm = new x86_Asm.Assembly(output, env);
			System.out.println(asm.toString());
		}



/*
		String jpl_code = "show sum[i : 65537, j : 65537, k : 65537, l : 65537] i + j + k + l";
		var output = Parser.parse_code( Lexer.Lex(jpl_code) );
		var env = TypeChecker.type_check(output);
		var asm = new x86_Asm.Assembly(output, env);
		System.out.println(asm.toString());
*/

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

class x86_Asm {

	public static class ASM_Exception extends RuntimeException {
		public ASM_Exception(String message) {
			super(message);
		}
	}

	public static class Assembly {
		List<String> data; // data segment
		List<ASM_Function> functions;
		Map<String, String> constant_names; // asm_val (unique number) --> const0, const1...
		int const_name_counter = 0;
		int jump_counter = 1;
		TypeChecker.Environment env;
		Stack global_stack;
		List<Parser.ASTNode> commands;

		public Assembly(List<Parser.ASTNode> commands, TypeChecker.Environment env) {
			data = new ArrayList<>();
			functions = new ArrayList<>();
			constant_names = new HashMap<>();
			this.commands = commands;
			this.env = env;
			generate_assembly();
		}

		public void generate_assembly() {
			ASM_Function main = new ASM_Function("jpl_main", this);
			main.run_cmds(commands);
			functions.add(main);
		}

		public String add_jump() {
			return ".jump" + jump_counter++;
		}

		// adds constant to data segment & makes sure its unique
		public <T> String add_constant(T value) {
			if(value instanceof String str) {
				String normalized = str.replace("\\", "\\\\").replace("\n", "\\n");
				String cst = "db `" + normalized + "`, 0";
				return _insert_constant(cst);
			}
			else if(value instanceof Float || value instanceof Integer || value instanceof Long || value instanceof Double) {
				String cst = "dq " + value;
				return _insert_constant(cst);
			}
			else throw new ASM_Exception("Tried to add constant of a strange type");
		}

		private String _insert_constant(String asm_val) {
			if(constant_names.containsKey(asm_val))
				return constant_names.get(asm_val);

			String cst = "const" + const_name_counter++;
			constant_names.put(asm_val, cst);
			data.add("" + cst + ": " + asm_val);
			return cst;
		}

		 @Override
		 public String toString() {
			String ret = "global jpl_main\n" +
					"global _jpl_main\n" +
					"extern _fail_assertion\n" +
					"extern _jpl_alloc\n" +
					"extern _get_time\n" +
					"extern _show\n" +
					"extern _print\n" +
					"extern _print_time\n" +
					"extern _read_image\n" +
					"extern _write_image\n" +
					"extern _fmod\n" +
					"extern _sqrt\n" +
					"extern _exp\n" +
					"extern _sin\n" +
					"extern _cos\n" +
					"extern _tan\n" +
					"extern _asin\n" +
					"extern _acos\n" +
					"extern _atan\n" +
					"extern _log\n" +
					"extern _pow\n" +
					"extern _atan2\n" +
					"extern _to_int\n" +
					"extern _to_float\n\n";

			ret += "section .data\n";
			for(String d : data)
				ret += d + "\n";
			ret += "\n";

			ret += "section .text\n";
			for(var func : functions) {
				ret += func.name + ":\n";
				ret += "_" + func.name + ":\n";
				for(String line : func.code) {
					if(!line.contains(":"))
						ret += "        "; // indentation. Not on labels
					ret += line + "\n";
				}
				ret += "\n";
			}
			return ret;
		 }


	}

	public static class StackArg {
		public int size; // size of argument
		public int offset; // offset of where to find argument
		public TypeChecker.TypeValue type; // type of argument

		public StackArg(int size, int offset, TypeChecker.TypeValue rtype) {
			this.size = size;
			this.offset = offset;
			this.type = rtype;
		}
	}

	public static class Register {
		String name;
		public TypeChecker.TypeValue type;

		public Register(String name, TypeChecker.TypeValue type) {
			this.name = name;
			this.type = type;
		}
	}

	public static class CallingConvention {
		public Object ret = null;  // either a StackArg or a Register
		public List<Object> args = new ArrayList<>();
		public int total_stack_size;

		public CallingConvention(List<TypeChecker.TypeValue> arg_types, TypeChecker.TypeValue ret_type) {
			String[] int_regs = {"rdi", "rsi", "rdx", "rcx", "r8", "r9"};
			String[] float_regs = {"xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7", "xmm8"};
			int int_args = 0; // counter
			int float_args = 0; // counter
			int offset = 0;

			// process return
			if(ret_type.type_name.equals("IntType") || ret_type.type_name.equals("BoolType"))
				ret = new Register("rax", ret_type);
			else if(ret_type.type_name.equals("FloatType"))
				ret = new Register("xmm0", ret_type);
			else if(ret_type.toString().contains("Array")) {
				int size = get_size(ret_type);
				offset += size;
				ret = new StackArg(size, offset, ret_type);
				int_args++;
			}

			// process arguments
			List<TypeChecker.TypeValue> stack_args = new ArrayList<>();
			for(var atype : arg_types) {
				if(atype.type_name.equals("IntType") || atype.type_name.equals("BoolType")
					&& int_args < int_regs.length /* have used up all registers yet? */) {
					args.add(new Register(int_regs[int_args], atype));
					int_args++;
				}
				else if(atype.type_name.equals("FloatType") && float_args < float_regs.length) {
					args.add(new Register(float_regs[float_args], atype));
					float_args++;
				}
				else {
					stack_args.add(atype);
					args.add(null); // gotta fix these later
				}
			}

			// reverse order of stack_args and make them actually stack args
			List<StackArg> stack_posns = new ArrayList<>();
			for(int i = stack_args.size() - 1; i >= 0; i--) {
				var atype = stack_args.get(i);
				int size = get_size(atype);
				offset += size;
				stack_posns.add(new StackArg(size, offset, atype));
			}

			// Fix null args with correct StackArg objects
			for(int i = 0; i < args.size(); i++) {
				if(args.get(i) == null)
					args.set(i, pop_list(stack_posns));
			}

			total_stack_size = offset;
		}
	}

	public static class ASM_Function {
		String name;
		List<String> code;
		Assembly parent;
		Stack stack;

		public ASM_Function(String name, Assembly parent) {
			this.name = name;
			code = new ArrayList<>();
			this.parent = parent;
			stack = new Stack(this);
		}

		public void add_line(String line) {
			code.add(line);
		}


		// for jpl_main
		public void run_cmds(List<Parser.ASTNode> commands) {
			parent.global_stack = stack; // gross

			var var_list = new ArrayList<String>();
			var_list.add("argnum");
			stack.add_lvalue(new Parser.ArrayLValue(0, 0, "args", var_list), -16);

			// Prologue
			code.add("push rbp");
			code.add("mov rbp, rsp");
			code.add("push r12");
			code.add("mov r12, rbp ; end of jpl_main prelude");

			stack.shadow.add("(IntType)"); // possible bug?
			stack.increment(8);

			for(var cmd : commands)
				gen_cmd((Parser.Cmd)cmd);

			if(stack.size > 8)
				code.add("add rsp, " + (stack.size - 8) + " ; Local variables");

			// Epilogue
			add_line("pop r12 ; begin jpl_main postlude");
			add_line("pop rbp");
			add_line("ret");
		}


		// put const value into register
		public <T> void const_into_register(String register, T value) {
			String name = parent.add_constant(value);
			if(value instanceof String)
				add_line("lea " + register + ", [rel " + name + "] ; " + "\'" + value + "\'");
			else
				add_line("mov " + register + ", [rel " + name + "] ; " + value);
		}

		// assert (message) into asm.
		// Must do comparison right right before call to create_assert
		// must do stuff that goes after label right after call to create_assert
		public void create_assert(String message, String jump_instruction) {
			String jump_label = parent.add_jump();
			add_line(jump_instruction + " " + jump_label);
			stack.align(0); // not sure if this can just be 8
			const_into_register("rdi", message);
			add_line("call _fail_assertion");
			stack.unalign();
			add_line(jump_label + ":");
		}

		// start and end are registers
		public void copy_data(int byte_size, String start, String end) {
			for(int i = byte_size-8; i >= 0; i -= 8) {
				add_line("mov r10, [" + start + " + " + i + "]");
				add_line("mov [" + end + " + " + i + "], r10");
			}
		}



		//
		//  COMMANDS
		//
		public void gen_cmd(Parser.Cmd cmd) {
			if(cmd instanceof Parser.ShowCmd showCmd)
				gen_cmd_show(showCmd);
			else if(cmd instanceof Parser.LetCmd letCmd)
				gen_cmd_let(letCmd);
			else if(cmd instanceof Parser.FnCmd fnCmd)
				gen_cmd_fn(fnCmd);
			else throw new ASM_Exception("Unimplemented command type");

		}

		public void gen_cmd_fn(Parser.FnCmd cmd) {
			ASM_Function asm_fn = new ASM_Function(cmd.identifier, parent);
			asm_fn.gen_cmd_fn_driven(cmd); // needed for asm code to appear in new function
			parent.functions.add(asm_fn);
		}
		public void gen_cmd_fn_driven(Parser.FnCmd cmd) {
			add_line("push rbp");
			add_line("mov rbp, rsp");
			CallingConvention cc;

			// build calling convention object
			try {
				TypeChecker.NameInfo tc_ni = parent.env.lookup(cmd.identifier);
				TypeChecker.FunctionInfo tc_fn = (TypeChecker.FunctionInfo)tc_ni;
				cc = new CallingConvention(tc_fn.argument_types, tc_fn.return_type);
			}
			catch(Exception ex) {
				throw new ASM_Exception("Error getting function information for " + cmd.identifier);
			}

			if(cc.ret instanceof StackArg) {
				stack.push("rdi", new TypeChecker.TypeValue("IntType"));
				stack.dict.put("$return", stack.size);
			}

			// receive args
			if(cmd.bindings.size() != cc.args.size())
				throw new ASM_Exception("Internal issue building arguments");

			for(int i = 0; i < cc.args.size(); i++) {
				var lvalue = cmd.bindings.get(i).lvalue;
				var arg = cc.args.get(i);

				if(arg instanceof StackArg sArg) {
					int offset = cc.total_stack_size - sArg.offset + 16;
					stack.add_lvalue(lvalue, -offset);
				}
				else if(arg instanceof Register regArg) {
					stack.push(regArg.name, regArg.type);
					stack.add_lvalue(lvalue, null);
				}
			}

			// process statements
			boolean has_return = false;
			for(var stmt : cmd.statements) {
				gen_stmt(stmt);
				if(stmt instanceof Parser.ReturnStmt)
					has_return = true;
			}

			// implicit return
			if(!has_return) {
				if(stack.size > 0)
					add_line("add rsp, " + stack.size + " ; Local variables");
				add_line("pop rbp");
				add_line("ret");
			}
		}
		public void gen_cmd_show(Parser.ShowCmd cmd) {
			stack.align(get_size(cmd.expr.type));
			gen_expr(cmd.expr);
			const_into_register("rdi", cmd.expr.type.toString()); // TODO: might be issues if type is array
			add_line("lea rsi, [rsp]");
			add_line("call _show");
			stack.free(cmd.expr.type, 1);
			stack.unalign();
		}
		public void gen_cmd_let(Parser.LetCmd cmd) {
			gen_expr(cmd.expr);
			stack.add_lvalue(cmd.lvalue, null);
		}


		//
		//  STATEMENTS
		//
		public void gen_stmt(Parser.Stmt stmt) {
			if(stmt instanceof Parser.LetStmt letStmt)
				gen_stmt_let(letStmt);
			else if(stmt instanceof Parser.ReturnStmt retStmt)
				gen_stmt_ret(retStmt);
			else throw new ASM_Exception("Unimplemented statement type");
		}

		public void gen_stmt_let(Parser.LetStmt stmt) {
			gen_expr(stmt.expr);
			stack.add_lvalue(stmt.lvalue, null);
		}
		public void gen_stmt_ret(Parser.ReturnStmt stmt) {
			gen_expr(stmt.expr);
			String type = stmt.expr.type.type_name;
			if(type.equals("IntType") || type.equals("BoolType"))
				stack.pop("rax", stmt.expr.type);
			else if(type.equals("FloatType"))
				stack.pop("xmm0", stmt.expr.type);
			else {
				int ret_loc = stack.dict.get("$return");
				add_line("mov rax, [rbp - " + ret_loc + "] ; Address to write return value into");
				copy_data(get_size(stmt.expr.type), "rsp", "rax");
			}
			add_line("add rsp, " + stack.size + " ; Local variables");
			add_line("pop rbp");
			add_line("ret");
		}

		//
		//  EXPRESSIONS
		//
		public void gen_expr(Parser.Expr expr) {
			if(expr instanceof Parser.IntExpr intExpr)
				gen_expr_int(intExpr);
			else if(expr instanceof Parser.FloatExpr floatExpr)
				gen_expr_float(floatExpr);
			else if(expr instanceof Parser.TrueExpr trueExpr)
				gen_expr_true(trueExpr);
			else if(expr instanceof Parser.FalseExpr falseExpr)
				gen_expr_false(falseExpr);
			else if(expr instanceof Parser.UnopExpr unopExpr)
				gen_expr_unop(unopExpr);
			else if(expr instanceof Parser.BinopExpr binopExpr)
				gen_expr_binop(binopExpr);
			else if(expr instanceof Parser.ArrayLiteralExpr ale)
				gen_expr_arrayliteral(ale);
			else if(expr instanceof Parser.VarExpr varExpr)
				gen_expr_var(varExpr);
			else if(expr instanceof Parser.CallExpr callExpr)
				gen_expr_call(callExpr);
			else if(expr instanceof Parser.IfExpr ifExpr)
				gen_expr_if(ifExpr);
			else if(expr instanceof Parser.ArrayIndexExpr indexExpr)
				gen_expr_index(indexExpr);
			else if(expr instanceof Parser.ArrayLoopExpr arrayLoopExpr)
				gen_expr_loop(arrayLoopExpr);
			else if(expr instanceof Parser.SumLoopExpr sumLoopExpr)
				gen_expr_loop(sumLoopExpr);
			else throw new ASM_Exception("Unimplemented expression type");
		}

		public void gen_expr_int(Parser.IntExpr expr) {
			const_into_register("rax", expr.integer);
			stack.push("rax", new TypeChecker.TypeValue("IntType"));
			stack.recharacterize(1, expr.type);
		}
		public void gen_expr_float(Parser.FloatExpr expr) {
			const_into_register("rax", expr.float_val);
			stack.push("rax", new TypeChecker.TypeValue("IntType")); // can't do nice workaround removing recharacterize because of this
			stack.recharacterize(1, expr.type);
		}
		public void gen_expr_true(Parser.TrueExpr expr) {
			const_into_register("rax", 1);
			stack.push("rax", new TypeChecker.TypeValue("IntType"));
			stack.recharacterize(1, expr.type);
		}
		public void gen_expr_false(Parser.FalseExpr expr) {
			const_into_register("rax", 0);
			stack.push("rax", new TypeChecker.TypeValue("IntType"));
			stack.recharacterize(1, expr.type);
		}
		public void gen_expr_unop(Parser.UnopExpr expr) {
			gen_expr(expr.expr); // puts inner type on top of stack
			if (expr.type.type_name.equals("BoolType")) { // operator is !
				stack.pop("rax", expr.expr.type);
				add_line("xor rax, 1"); // flip bool in ASM
				stack.push("rax", expr.type);
			} else if (expr.type.type_name.equals("IntType")) {
				stack.pop("rax", expr.expr.type);
				add_line("neg rax");
				stack.push("rax", expr.type);
			} else if (expr.type.type_name.equals("FloatType")) {
				stack.pop("xmm1", expr.expr.type);
				add_line("pxor xmm0, xmm0");
				add_line("subsd xmm0, xmm1");
				stack.push("xmm0", expr.type);
			} else throw new ASM_Exception("unimplemented unop expression type");
		}
		public void gen_expr_binop(Parser.BinopExpr expr) {
			String type = expr.expr1.type.type_name;

			// strangeness for fmod
			if(expr.op.equals("%") && type.equals("FloatType"))
				stack.align(0);

			String reg1;
			String reg2;
			if(type.equals("FloatType")) {
				reg1 = "xmm0";
				reg2 = "xmm1";
			} else {
				reg1 = "rax";
				reg2 = "r10";
			}

			// put onto stack in order
			gen_expr(expr.expr2);
			gen_expr(expr.expr1);
			// pop off stack in order
			stack.pop(reg1, expr.expr1.type);
			stack.pop(reg2, expr.expr2.type);


			// Comparisons
			String[] comparison_ops = {"<", ">", "<=", ">=", "==", "!="};
			if(Arrays.asList(comparison_ops).contains(expr.op)) {
				if(type.equals("IntType") || type.equals("BoolType")) {
					add_line("cmp rax, r10");

					// setcc al
					if(expr.op.equals("<"))
						add_line("setl al");
					else if(expr.op.equals(">"))
						add_line("setg al");
					else if(expr.op.equals("<="))
						add_line("setle al");
					else if(expr.op.equals(">="))
						add_line("setge al");
					else if(expr.op.equals("=="))
						add_line("sete al");
					else if(expr.op.equals("!="))
						add_line("setne al");

					add_line("and rax, 1");
				}
				else if(type.equals("FloatType")) {
					if(expr.op.equals("<")) {
						add_line("cmpltsd xmm0, xmm1");
						add_line("movq rax, xmm0");
					}
					else if(expr.op.equals(">")) {
						add_line("cmpltsd xmm1, xmm0");
						add_line("movq rax, xmm1");
					}
					else if(expr.op.equals("<=")) {
						add_line("cmplesd xmm0, xmm1");
						add_line("movq rax, xmm0");
					}
					else if(expr.op.equals(">=")) {
						add_line("cmplesd xmm1, xmm0");
						add_line("movq rax, xmm1");
					}
					else if(expr.op.equals("==")) {
						add_line("cmpeqsd xmm0, xmm1");
						add_line("movq rax, xmm0");
					}
					else if(expr.op.equals("!=")) {
						add_line("cmpneqsd xmm0, xmm1");
						add_line("movq rax, xmm0");
					}
					add_line("and rax, 1");
				}
				else throw new ASM_Exception("Unimplemented type for comparisons");
			}
			// Math
			else {
				if(type.equals("IntType")) {
					if(expr.op.equals("+"))
						add_line("add rax, r10");
					else if(expr.op.equals("-"))
						add_line("sub rax, r10");
					else if(expr.op.equals("*"))
						add_line("imul rax, r10");
					else if(expr.op.equals("/")) {
						add_line("cmp r10, 0");
						create_assert("divide by zero", "jne");
						add_line("cqo");
						add_line("idiv r10");
					}
					else if(expr.op.equals("%")) {
						add_line("cmp r10, 0");
						create_assert("mod by zero", "jne");
						add_line("cqo");
						add_line("idiv r10");
						add_line("mov rax, rdx");
					}
					else throw new ASM_Exception("Unimplemented operation for binop int");
				}
				else if(type.equals("FloatType")) {
					if(expr.op.equals("+"))
						add_line("addsd xmm0, xmm1");
					else if(expr.op.equals("-"))
						add_line("subsd xmm0, xmm1");
					else if(expr.op.equals("*"))
						add_line("mulsd xmm0, xmm1");
					else if(expr.op.equals("/"))
						add_line("divsd xmm0, xmm1");
					else if(expr.op.equals("%")) {
						add_line("call _fmod");
						stack.unalign();
					}

					else throw new ASM_Exception("Unimplemented operation for binop float");
				}
				else throw new ASM_Exception("Unimplemented type for math");
			}

			if(expr.type.type_name.equals("IntType") || expr.type.type_name.equals("BoolType"))
				stack.push("rax", expr.type);
			if(expr.type.type_name.equals("FloatType"))
				stack.push("xmm0", expr.type);
		}
		public void gen_expr_arrayliteral(Parser.ArrayLiteralExpr expr) {
			for(int i = expr.exprs.size() - 1; i >= 0; i--)
				gen_expr(expr.exprs.get(i));
			int byte_size = expr.exprs.size() * get_size(expr.exprs.get(0).type);
			add_line("mov rdi, " + byte_size);

			stack.align(0);
			add_line("call _jpl_alloc");
			stack.unalign();

			copy_data(byte_size, "rsp", "rax");
			stack.free(expr.exprs.get(0).type, expr.exprs.size());
			// mystery line?   add_line("mov rdi, " + byte_size);
			stack.push("rax", new TypeChecker.TypeValue("IntType"));
			add_line("mov rax, " + expr.exprs.size());
			stack.push("rax", new TypeChecker.TypeValue("IntType"));
			stack.recharacterize(2, expr.type);
		}
		public void gen_expr_var(Parser.VarExpr expr) {
			int byte_size = get_size(expr.type);
			stack.alloc(expr.type, 1);

			if(stack.dict.containsKey(expr.str))  // local variable (to function)
				copy_data(byte_size, "rbp - " + stack.dict.get(expr.str) , "rsp");
			else // global variable
				copy_data(byte_size, "r12 - " + parent.global_stack.dict.get(expr.str), "rsp");
		}
		public void gen_expr_call(Parser.CallExpr expr) {
			CallingConvention cc;
			TypeChecker.TypeValue rtype;

			// build calling convention
			try {
				TypeChecker.NameInfo tc_ni = parent.env.lookup(expr.identifier);
				TypeChecker.FunctionInfo tc_fn = (TypeChecker.FunctionInfo)tc_ni;
				cc = new CallingConvention(tc_fn.argument_types, tc_fn.return_type);
				rtype = tc_fn.return_type;
			}
			catch(Exception ex) {
				throw new ASM_Exception("Trouble building calling convention inside call expression");
			}

			// prepare stack with return value
			int offset = 0;
			if(cc.ret instanceof StackArg sArg) {
				stack.alloc(sArg.type, 1);
				offset = sArg.size;
			} //else
				//stack.shadow.add(rtype.toString()); // in slides but incorrect

			stack.align(cc.total_stack_size - offset);

			// generate code for stack args (right to left)
			if(expr.expressions.size() != cc.args.size())
				throw new ASM_Exception("Internal issue generating arg code, sizes don't match");
			for(int i = expr.expressions.size() - 1; i >= 0; i--) {
				if(cc.args.get(i) instanceof StackArg)
					gen_expr(expr.expressions.get(i));
			}

			// generate code for other args (right to left)
			for(int i = expr.expressions.size() - 1; i >= 0; i--) {
				if(cc.args.get(i) instanceof Register)
					gen_expr(expr.expressions.get(i));
			}

			// Pop register args into correct registers
			for(int i = 0; i < expr.expressions.size(); i++) {
				if(cc.args.get(i) instanceof Register reg)
					stack.pop(reg.name, reg.type);
			}

			if(cc.ret instanceof StackArg sArg) {
				offset = cc.total_stack_size - sArg.offset + stack.padding_stack.get(stack.padding_stack.size()-1); // seems to be correct
				add_line("lea rdi, [rsp + " + offset + "]");
			}

			add_line("call _" + expr.identifier);

			// free stack args one by one
			for(int i = 0; i < expr.expressions.size(); i++) {
				if(cc.args.get(i) instanceof StackArg sArg)
					stack.free(sArg.type, 1);
			}

			stack.unalign();
			// push ret if register to stack
			if(cc.ret instanceof Register reg)
				stack.push(reg.name, reg.type);
		}
		public void gen_expr_if(Parser.IfExpr expr) {
			gen_expr(expr.expr1); // if expr
			stack.pop("rax", new TypeChecker.TypeValue("BoolType"));
			add_line("cmp rax, 0");
			String else_label = parent.add_jump();
			String end_label = parent.add_jump();
			add_line("je " + else_label);
			gen_expr(expr.expr2); // then expression
			stack.decrement(get_size(expr.type));
			pop_list(stack.shadow); // could be culprit
			add_line("jmp " + end_label);
			add_line(else_label + ":");
			gen_expr(expr.expr3); // else expression
			add_line(end_label + ":");
		}
		public void gen_expr_index(Parser.ArrayIndexExpr expr) {
			if(!(expr.expr.type instanceof TypeChecker.ArrayType))
				throw new ASM_Exception("ArrayIndexExpr got non-array");

			gen_expr(expr.expr);
			int gap = ((TypeChecker.ArrayType)expr.expr.type).rank;

			// generate expressions backwards (put on stack in correct order)
			for(int i = expr.expressions.size() - 1; i >= 0; i--)
				gen_expr(expr.expressions.get(i));

			for(int i = 0; i < expr.expressions.size(); i++) {
				add_line("mov rax, [rsp + " + (i * 8) + "]");
				add_line("cmp rax, 0");
				create_assert("negative array index", "jge");
				add_line("cmp rax, [rsp + " + ((i + gap)*8) + "]");
				create_assert("index too large", "jl");
			}

			asm_index((TypeChecker.ArrayType)expr.expr.type, 0, gap);

			// free in opposite order we added
			for(var e : expr.expressions)
				stack.free(e.type, 1);
			stack.free(expr.expr.type, 1);

			TypeChecker.ArrayType expr_arr = (TypeChecker.ArrayType)expr.expr.type; // could be the culprit
			stack.alloc(expr_arr.inner_type, 1);
			copy_data(get_size(expr_arr.inner_type), "rax", "rsp");
		}
		public void gen_expr_loop(Parser.Expr expr) {
			if(!(expr instanceof Parser.ArrayLoopExpr) && !(expr instanceof Parser.SumLoopExpr))
				throw new ASM_Exception("gen_expr_loop requires array loop or sum loop");

			add_line("; Allocating 8 bytes for the pointer");
			stack.alloc(new TypeChecker.TypeValue("IntType"), 1);
			if(expr instanceof Parser.SumLoopExpr sumLoopExpr)
				stack.recharacterize(1, sumLoopExpr.type);

			List<String> variables = null;
			List<Parser.Expr> expressions = null;
			Parser.Expr loop_expr = null;
			if(expr instanceof Parser.ArrayLoopExpr arrayLoopExpr) {
				variables = arrayLoopExpr.variables;
				expressions = arrayLoopExpr.expressions;
				loop_expr = arrayLoopExpr.expr;
			}
			else if(expr instanceof Parser.SumLoopExpr sumLoopExpr) {
				variables = sumLoopExpr.variables;
				expressions = sumLoopExpr.expressions;
				loop_expr = sumLoopExpr.expr;
			}
			else throw new ASM_Exception("Loop type must be arrayLoopExpr or sumLoopExpr");

			if(variables.size() != expressions.size())
				throw new ASM_Exception("internal error, sizes don't match");

			for(int i = variables.size()-1; i >= 0; i--) {
				add_line("; Computing bound for " + variables.get(i));
				gen_expr(expressions.get(i));
				add_line("mov rax, [rsp]");
				add_line("cmp rax, 0");
				create_assert("non-positive loop bound", "jg");
			}

			int n = variables.size();
			if(expr instanceof Parser.ArrayLoopExpr arrayLoopExpr) {
				add_line("; Computing total size of heap memory to allocate");
				int offset = 0; // not actually needed it seems // TODO: needed for einsum
				add_line("mov rdi, " + (get_size(arrayLoopExpr.expr.type)) + " ; sizeof " + arrayLoopExpr.expr.type);
				for(int i = 0; i < n; i++) {
					add_line("imul rdi, [rsp + " + (offset * 8) + " + " + (i*8) + "] ; multiply by BOUND");
					create_assert("overflow computing array size", "jno");
				}
				// rdi holds array size
				stack.align(0);
				add_line("call _jpl_alloc ; Put pointer to heap space in RAX");
				stack.unalign();
			}
			else {
				add_line("; Initialize sum to 0");
				add_line("mov rax, 0");
			}
			add_line("mov [rsp + " + (n*8) + "], rax ; Move to pre-allocated space");

			for(int i = variables.size()-1; i >= 0; i--) {
				add_line("; Initialize " + variables.get(i) + " to 0");
				add_line("mov rax, 0");
				stack.push("rax", new TypeChecker.TypeValue("IntType"));
				stack.dict.put(variables.get(i), stack.size);
			}

			String lbl_start = parent.add_jump();
			add_line(lbl_start + ": ; Begin body of loop");
			add_line("; Compute loop body");
			gen_expr(loop_expr);

			if(expr instanceof Parser.ArrayLoopExpr arrayLoopExpr) {
				int offset = get_size(arrayLoopExpr.expr.type);
				add_line("; Index to store in");
				asm_index((TypeChecker.ArrayType)arrayLoopExpr.type, offset, null);
				add_line("; Move body (" + offset + "bytes) to index");
				copy_data(offset, "rsp", "rax");
				stack.free(arrayLoopExpr.expr.type, 1);
			}
			else if(expr instanceof Parser.SumLoopExpr sumLoopExpr1) {
				if(sumLoopExpr1.expr.type.type_name.equals("IntType")) {
					stack.pop("rax", sumLoopExpr1.expr.type);
					add_line("add [rsp + " + (2 * n * 8) + "], rax ; Add loop body to sum");
				}
				else {
					stack.pop("xmm0", sumLoopExpr1.expr.type);
					add_line("addsd xmm0, [rsp + " + (2 * n * 8) + "] ; Load sum");
					add_line("movsd [rsp + " + (2 * n * 8) + "], xmm0 ; Save sum");
				}
			}

			int last_offset = variables.size() - 1;
			String last_var = variables.get(last_offset);

			add_line("; Increment " + last_var);
			add_line("add qword [rsp + " + (last_offset * 8) + "], 1");
			for(int i = n - 1; i >= 0; i--) {
				String var = variables.get(i);
				int offset = i;
				add_line("; Compare " + var + " to its bound");
				add_line("mov rax, [rsp + " + (offset * 8) + "]");
				add_line("cmp rax, [rsp + " + ((offset + n) * 8) + "]");
				add_line("jl " + lbl_start + " ; If " + var + " < bound, next iter");
				if(i != 0) {
					String next_var = variables.get(i-1);
					int next_offset = i-1;
					add_line("mov qword [rsp + " + (offset * 8) + "], 0 ; " + var + " = 0");
					add_line("add qword [rsp + " + (next_offset * 8) + "], 1 ; " + next_var + " = 0");
				}
			}

			add_line("; End body of loop");
			add_line("; Free all loop variables");
			stack.free(new TypeChecker.TypeValue("IntType"), variables.size());
			if(expr instanceof Parser.SumLoopExpr sumLoopExpr1) {
				add_line("; Free all loop bounds");
				stack.free(new TypeChecker.TypeValue("IntType"), variables.size());
			}
			else if(expr instanceof Parser.ArrayLoopExpr arrayLoopExpr) {
				stack.recharacterize(((TypeChecker.ArrayType)arrayLoopExpr.type).rank + 1, expr.type);
			}
			add_line("; left on stack");

		}


		// default offset = 0
		// eventually needs max_sizes for optimization (also needed in loops)
		public void asm_index(TypeChecker.ArrayType type, int offset, Integer gap) {
			int n = type.rank;
			if(gap == null)
				gap = n;
			add_line("mov rax, 0");
			for(int i = 0; i < gap; i++) {
				add_line("imul rax, [rsp + " + (offset + i * 8 + gap * 8) + "] ; No overflow if indices in bounds");
				add_line("add rax, [rsp + " + (offset + i * 8) + "]");
			}
			add_line("imul rax, " + get_size(type.inner_type));
			add_line("add rax, [rsp + " + (offset + n * 8 + gap * 8) + "]");
		}

	}


	public static class Stack {
		Map<String, Integer> dict; // names that show up in program --> stack positions
		ASM_Function func;
		List<Integer> padding_stack;
		List<String> shadow;
		int size = 0;

		public Stack(ASM_Function func) {
			dict = new HashMap<>();
			this.func = func;
			padding_stack = new ArrayList<>();
			shadow = new ArrayList<>();
		}

		public void increment(int bytes) {
			size += bytes;
		}

		public void decrement(int bytes) {
			size -= bytes;
		}

		public void align(int extra) {
			int leftover = (size + extra) % 16;
			if(leftover == 0) {
				padding_stack.add(0); // no extra padding; Line needed for unalign
			} else {
				int padding = 16 - leftover;
				increment(padding);
				padding_stack.add(padding);
				shadow.add("padding");
				func.add_line("sub rsp, " + padding + " ; Add alignment");
			}
		}

		public void unalign() {
			int padding = pop_list(padding_stack);
			// if there was padding must update shadow
			if(padding != 0) {
				decrement(padding);
				if(!pop_list(shadow).equals("padding"))
					throw new ASM_Exception("There should have been padding in shadow");
				func.add_line("add rsp, " + padding + " ; Remove alignment");
			}
		}

		// push register onto stack
		public void push(String register, TypeChecker.TypeValue type) {
			shadow.add(type.toString());
			increment(8);
			if(type.type_name.equals("FloatType")) { // xmm register
				func.add_line("sub rsp, 8");
				func.add_line("movsd [rsp], " + register);
			}
			else if(type.type_name.equals("IntType") || type.type_name.equals("BoolType")) {
				func.add_line("push " + register);
			}
			else throw new ASM_Exception(type.type_name + " cannot be pushed onto the stack");
		}

		// pop primitive off the stack and put into register
		public void pop(String register, TypeChecker.TypeValue type) {
			decrement(8);
			String top_type = pop_list(shadow);
			if(!top_type.equals(type.toString()))
				throw new ASM_Exception("Type mismatch when popping off the stack");

			if(type.type_name.equals("FloatType")) { // xmm register
				func.add_line("movsd " + register + ", [rsp]");
				func.add_line("add rsp, 8");
			}
			else if(type.type_name.equals("IntType") || type.type_name.equals("BoolType")) {
				func.add_line("pop " + register);
			}
			else throw new ASM_Exception(type.type_name + " cannot be put into a register");
		}

		// replace k top with type
		// maybe TypeValue is better here?
		public void recharacterize(int k, TypeChecker.TypeValue type) {
			for(int i = 0; i < k; i++)
				pop_list(shadow);
			shadow.add(type.toString());
		}

		public void alloc(TypeChecker.TypeValue type, int amount) {
			int amt = get_size(type) * amount;
			func.add_line("sub rsp, " + amt);
			increment(amt);
			for(int i = 0; i < amount; i++) // arrays
				shadow.add(type.toString());
		}

		public void free(TypeChecker.TypeValue type, int amount) {
			int amt = get_size(type) * amount;
			func.add_line("add rsp, " + amt);
			decrement(amt);
			for(int i = 0; i < amount; i++) { // array removal
				String s = pop_list(shadow);
				if(!s.equals(type.toString()))
					throw new ASM_Exception("Freeing type " + s + "; expected " + type.toString());
			}
		}

		// Set pos == null for default
		public void add_lvalue(Parser.LValue lvalue, Integer base) {
			if(base == null)
				base = size;

			if(lvalue instanceof Parser.VarLValue vlv) {
				dict.put(vlv.identifier, base);
			}
			else if(lvalue instanceof Parser.ArrayLValue alv) {
				dict.put(alv.identifier, base);
				for (String variable : alv.variables) {
					dict.put(variable, base);
					base -= 8;
				}
			}
		}

	}

	public static int get_size(TypeChecker.TypeValue type) {
		if(type instanceof TypeChecker.ArrayType arrtype)
			return 8 + (arrtype.rank * 8);
		else
			return 8;
	}

	// remove and return latest value from stack list
	public static <T> T pop_list(List<T> stack) {
		return stack.remove(stack.size() -1);
	}

}

class C_Code {

	public static String convert_to_c(List<Parser.ASTNode> commands, TypeChecker.Environment env) throws C_Exception {
		try {
			C_Program program = new C_Program(env);
			C_Fn jpl_main = new C_Fn("jpl_main", program);
			jpl_main.Begin(commands);

			String headers = "#include <math.h>\n" +
					"#include <stdbool.h>\n" +
					"#include <stdint.h>\n" +
					"#include <stdio.h>\n" +
					"#include \"rt/runtime.h\"\n" +
					"\n" +
					"typedef struct { } void_t;\n\n";

			// gather struct code
			String structs = "";
			for (Map.Entry entry : program.structs.entrySet()) {
				List<String> lines = (List<String>)entry.getValue();
				for(String str : lines)
					structs += str + "\n";
				structs += "\n";
			}

			// gather function code
			String functions = "";
			program.functions.add(jpl_main); // only gets added at the end
			for(C_Fn fn : program.functions) {
				for(String line : fn.code) {
					functions += line + "\n";
				}
				functions += "\n";
			}

			String output = headers + structs + functions.substring(0, functions.length()-1);
			return output;
		}
		catch (Exception e) {
			System.out.println("Compilation failed\n");
			throw new C_Exception("Caught Exception: " + e.toString());
		}
	}

	private static class C_Exception extends Exception {

		private String msg = "Compilation failed\n";

		public C_Exception(String msg) {
			this.msg += "C conversion error: " + msg;
		}

		@Override
		public String toString() {
			return msg;
		}
	}

	private static String indent = "    ";
	private static String get_c_type(TypeChecker.TypeValue type) throws C_Exception {
		if(type instanceof TypeChecker.ArrayType) {
			var arr_type = (TypeChecker.ArrayType)type;

			// encapsulate TypeValue by expr to recursively call get_c_type
			Parser.Expr temp = new Parser.Expr();
			temp.type = arr_type.inner_type;

			return "_a" + arr_type.rank + "_" + get_c_type(temp);
		} else if(type instanceof TypeChecker.StructType) {
			return ((TypeChecker.StructType)type).struct_name;
		} else {
			return get_c_type(type.type_name);
		}
	}
	private static String get_c_type(Parser.Expr expr) throws C_Exception {
		if(expr.type instanceof TypeChecker.ArrayType) {
			var arr_type = (TypeChecker.ArrayType)expr.type;

			// encapsulate TypeValue by expr to recursively call get_c_type
			Parser.Expr temp = new Parser.Expr();
			temp.type = arr_type.inner_type;

			return "_a" + arr_type.rank + "_" + get_c_type(temp);
		} else if(expr.type instanceof TypeChecker.StructType) {
			return ((TypeChecker.StructType)expr.type).struct_name;
		} else {
			return get_c_type(expr.type.type_name);
		}
	}
	private static String get_c_type(String jpl_type) throws C_Exception {
		switch(jpl_type) {
			case "IntType": return "int64_t";
			case "FloatType": return "double";
			case "BoolType": return "bool";
			case "VoidType": return "void_t";
			default: throw new C_Exception("Type does not exist");
		}
	}


	private static class C_Program {
		List<C_Fn> functions = new ArrayList<>();
		LinkedHashMap<String, List<String>> structs = new LinkedHashMap<>();
		int jump_ctr = 1;
		TypeChecker.Environment env;
		HashMap<String, String> name_map = new HashMap<>(); // jpl var name to the c code var name
		HashMap<String, Parser.Expr> expr_map = new HashMap(); // c code var name to the Expr object
		HashMap<String, Parser.StructCmd> struct_more_info = new HashMap<String, Parser.StructCmd>(); // struct name to structcmd


		public C_Program(TypeChecker.Environment env) {
			this.env = env;

			// add rbga struct
			List<String> variables = Arrays.asList("r", "g", "b", "a");
			Parser.Type floatType = new Parser.FloatType(0, 0);
			List<Parser.Type> types = Arrays.asList(floatType, floatType, floatType, floatType);
			Parser.StructCmd structCmd = new Parser.StructCmd(0, 0, "rgba", variables, types);
			struct_more_info.put("rgba", structCmd);
		}

		public String add_struct(int rank, String c_type) {
			String key = "_a" + rank + "_" + c_type;
			if(structs.containsKey(key))
				return key;

			List<String> struct_code = new ArrayList<>();
			struct_code.add("typedef struct {");
			for(int i = 0; i < rank; i++)
				struct_code.add(indent + "int64_t d" + i + ";");
			struct_code.add(indent + c_type + " *data;");
			struct_code.add("} " + key + ";");
			structs.put(key, struct_code);
			return key;
		}

		public String add_jump() {
			return "_jump" + jump_ctr++;
		}
	}

	private static class C_Fn {
		String name;
		C_Program parent;
		List<String> code = new ArrayList<>();
		int name_ctr = 0;
		HashMap<String, String> p_name_map = new HashMap<>(); // jpl var name to the c code var name; personal name map
		//TypeChecker.Environment env = new TypeChecker.Environment();


		public C_Fn(String name, C_Program parent) {
			this.name = name;
			this.parent = parent;
		}

		public void Begin(List<Parser.ASTNode> commands) throws C_Exception {
			p_name_map = parent.name_map; // jpl_main has actual stuff

			code.add("void jpl_main(struct args args) {");
			p_name_map.put("args","args");
			p_name_map.put("argnum","args.d0");
			for(var cmd : commands) {
				if(!(cmd instanceof Parser.Cmd)) throw new C_Exception("Unknown error: expected command");
				cvt_cmd((Parser.Cmd)cmd);
			}
			code.add("}");
		}

		private void begin_not_main(List<Parser.Stmt> statements, boolean isVoid) throws C_Exception {
			for(String i : parent.name_map.keySet()) {
				if(!(parent.name_map.get(i)).contains("."))
					p_name_map.put(i, i);
				else
					p_name_map.put(i, parent.name_map.get(i));
			}

			// statements
			for(var stmt : statements) {
				cvt_stmt(stmt);
			}

			if(isVoid) {
				String c_name = gensym();
				code.add(indent + "void_t " + c_name + " = {};" );
				code.add(indent + "return " + c_name + ";");
			}
		}

		private void internal_begin() {
			// for functions that can only do statements (not main function)
		}

		private String gensym() {
			return "_" + name_ctr++;
		}


		/*
			CONVERT COMMANDS
		 */
		private void cvt_cmd(Parser.Cmd cmd) throws C_Exception {
			if(cmd instanceof Parser.ShowCmd)
				cvt_cmd_show((Parser.ShowCmd)cmd);
			else if(cmd instanceof Parser.LetCmd)
				cvt_cmd_let((Parser.LetCmd)cmd);
			else if(cmd instanceof Parser.AssertCmd)
				cvt_cmd_assert((Parser.AssertCmd)cmd);
			else if(cmd instanceof Parser.PrintCmd)
				cvt_cmd_print((Parser.PrintCmd)cmd);
			else if(cmd instanceof Parser.StructCmd)
				cvt_cmd_struct((Parser.StructCmd)cmd);
			else if(cmd instanceof Parser.FnCmd)
				cvt_cmd_fn((Parser.FnCmd)cmd);
			else if(cmd instanceof Parser.ReadCmd)
				cvt_cmd_read((Parser.ReadCmd)cmd);
			else if(cmd instanceof Parser.WriteCmd)
				cvt_cmd_write((Parser.WriteCmd)cmd);
			else if(cmd instanceof Parser.TimeCmd)
				cvt_cmd_time((Parser.TimeCmd)cmd);
		}

		private void cvt_cmd_fn(Parser.FnCmd cmd) throws C_Exception {
			C_Fn new_fn = new C_Fn(cmd.identifier, parent);
			parent.functions.add(new_fn);

			// header
			String c_type_return = type_helper(cmd.return_value);
			String inputs = "";
			for(var binding : cmd.bindings) {
				inputs += type_helper(binding.type) + " " + binding.lvalue.identifier + ", ";
				// save arg bindings
				p_name_map.put(binding.lvalue.identifier, binding.lvalue.identifier);
				// save size binding (yes seem to only be able to be 1 dimension using 'let')
				if(binding.lvalue instanceof Parser.ArrayLValue blv) {
					for(int i = 0; i < blv.variables.size(); i++) {
						String dim_name = blv.variables.get(i);
						p_name_map.put(dim_name, binding.lvalue.identifier+".d"+i);
					}
				}
			}
			if(cmd.bindings.size() > 0)
				inputs = inputs.substring(0, inputs.length()-2);

			new_fn.code.add(c_type_return + " " + cmd.identifier + "(" + inputs + ") {");

			// statements and return
			boolean isVoid = cmd.return_value.toString().equals("(VoidType)");
			new_fn.begin_not_main(cmd.statements, isVoid);
			new_fn.code.add("}");
		}

		private void cvt_cmd_let(Parser.LetCmd cmd) throws C_Exception {
			String c_name = cvt_expr(cmd.expr);

			// save variable bindings
			p_name_map.put(cmd.lvalue.identifier, c_name);
			parent.expr_map.put(c_name, cmd.expr);

			// save size binding (yes seem to only be able to be 1 dimension using 'let')
			if(cmd.lvalue instanceof Parser.ArrayLValue) {
				var arrlval = (Parser.ArrayLValue)cmd.lvalue;

				for(int i = 0; i < arrlval.variables.size(); i++) {
					String dim_name = arrlval.variables.get(i);
					p_name_map.put(dim_name, c_name+".d"+i);
				}
			}
		}

		private void cvt_cmd_assert(Parser.AssertCmd cmd) throws C_Exception {
			String c_name = cvt_expr(cmd.expr);
			code.add(indent + "if (0 != " + c_name + ")");
			String c_jump = parent.add_jump();
			code.add(indent + "goto " + c_jump + ";");
			code.add(indent + "fail_assertion(" + cmd.str + ");");
			code.add(indent + c_jump + ":;");
		}

		private void cvt_cmd_print(Parser.PrintCmd cmd) {
			code.add(indent + "print(" + cmd.str + ");");
		}

		private void cvt_cmd_struct(Parser.StructCmd cmd) throws C_Exception {
			parent.struct_more_info.put(cmd.identifier, cmd);

			List<String> struct_code = new ArrayList<>();
			struct_code.add("typedef struct {");
			for(int i = 0; i < cmd.variables.size(); i++) {
				String c_type = type_helper(cmd.types.get(i));
				struct_code.add(indent + c_type + " " + cmd.variables.get(i) + ";");
			}
			struct_code.add("} " + cmd.identifier + ";");
			parent.structs.put(cmd.identifier, struct_code);
		}

		// get c type from parser type
		private String type_helper(Parser.Type type) throws C_Exception {
			if(type instanceof Parser.IntType)
				return "int64_t";
			else if(type instanceof Parser.FloatType)
				return "double";
			else if(type instanceof Parser.BoolType)
				return "bool";
			else if(type instanceof Parser.VoidType)
				return "void_t";
			else if(type instanceof Parser.StructType)
				return ((Parser.StructType)type).struct_name;
			else if(type instanceof Parser.ArrayType) {
				var arrType = (Parser.ArrayType)type;
				String c_inner_type = type_helper(arrType.type); // get type for inner type
				return parent.add_struct(arrType.dimension, c_inner_type); //"_a" + arrType.dimension + "_" + c_inner_type;
			}
			else throw new C_Exception("Unknown exception: no such Parser.Type");
		}

		private void cvt_cmd_show(Parser.ShowCmd cmd) throws C_Exception {
			String c_name = cvt_expr(cmd.expr);

			String tc_type = cmd.expr.type.toString();

			for(int i = 0; i < 3; i++) {
				tc_type = passover_replace(tc_type);
			}

			code.add(indent + "show(" + "\"" + tc_type + "\", &" + c_name + ");");
		}

		private String passover_replace(String tc_type) {
			// find structs in string
			List<String> struct_types = new ArrayList<>(); // structs to replace with TupleType: (StructType ipair)
			List<String> structs  = new ArrayList<>(); // actual struct names: ipair

			int from = 0;
			while(from < tc_type.length()) {
				int idx = tc_type.indexOf("(StructType ", from);
				if(idx == -1)
					break;
				else { // found structtype...
					String sub = tc_type.substring(idx, tc_type.indexOf(")", idx) + 1);
					struct_types.add(sub);
					from = idx + 5;
				}
			}

			for(String s : struct_types)
				structs.add(s.substring(12, s.length()-1));

			// create replacements
			List<String> replacements = new ArrayList<>();
			for(int i = 0; i < structs.size(); i++) {
				String str = "(TupleType";
				Parser.StructCmd struct_cmd = parent.struct_more_info.get(structs.get(i));
				for(Parser.Type inner : struct_cmd.types)
					str += " " + inner.toString(); // (IntType)
				if(struct_cmd.types.size() > 0)
					str += ")";
				else
					str += " )";
				replacements.add(str);
			}

			// replace
			String output = tc_type;
			for(int i = 0; i < structs.size(); i++) {
				output = output.replace(struct_types.get(i), replacements.get(i));
			}
			return output;
		}

		private void cvt_cmd_read(Parser.ReadCmd cmd) {
			String c_name = gensym();
			code.add(indent + "_a2_rgba " + c_name + " = read_image(" + cmd.read_file + ");");

			// save variable bindings
			p_name_map.put(cmd.lvalue.identifier, c_name);

			// bind height + width (has to be 2 because image is 2d array)
			if(cmd.lvalue instanceof Parser.ArrayLValue arrlval) {
				for(int i = 0; i < arrlval.variables.size(); i++) {
					String dim_name = arrlval.variables.get(i);
					code.add(indent + "int64_t " + dim_name + " = " + c_name + ".d"+i + ";");
					// save size bindings
					p_name_map.put(dim_name, c_name+".d"+i);
				}
			}
		}

		private void cvt_cmd_write(Parser.WriteCmd cmd) throws C_Exception {
			String c_name = cvt_expr(cmd.expr);
			code.add(indent + "write_image(" + c_name + ", " + cmd.write_file + ");");
		}

		private void cvt_cmd_time(Parser.TimeCmd cmd) throws C_Exception {
			String c_name1 = gensym();
			code.add(indent + "double " + c_name1 + " = get_time();");
			cvt_cmd(cmd.cmd);
			String c_name2 = gensym();
			code.add(indent + "double " + c_name2 + " = get_time();");
			code.add(indent + "print_time(" + c_name2 + " - " + c_name1 + ");");
		}

		/*
			CONVERT STATEMENTS
		 */
		private void cvt_stmt(Parser.Stmt stmt) throws C_Exception {
			if(stmt instanceof Parser.LetStmt)
				cvt_stmt_let((Parser.LetStmt)stmt);
			else if(stmt instanceof Parser.AssertStmt)
				cvt_stmt_assert((Parser.AssertStmt)stmt);
			else if(stmt instanceof Parser.ReturnStmt)
				cvt_stmt_return((Parser.ReturnStmt)stmt);
			else throw new C_Exception("Unknown exception: statement type not known");
		}

		private void cvt_stmt_let(Parser.LetStmt stmt) throws C_Exception {
			String c_name = cvt_expr(stmt.expr);

			// save variable bindings
			p_name_map.put(stmt.lvalue.identifier, c_name); // might have to remove from parent map later

			// save size binding (yes seem to only be able to be 1 dimension using 'let')
			if(stmt.lvalue instanceof Parser.ArrayLValue) {
				var arrlval = (Parser.ArrayLValue)stmt.lvalue;

				for(int i = 0; i < arrlval.variables.size(); i++) {
					String dim_name = arrlval.variables.get(i);
					p_name_map.put(dim_name, c_name+".d"+i);
				}
			}
		}
		private void cvt_stmt_assert(Parser.AssertStmt stmt) throws C_Exception {
			String c_name = cvt_expr(stmt.expr);
			code.add(indent + "if (0 != " + c_name + ")");
			String c_jump = parent.add_jump();
			code.add(indent + "goto " + c_jump + ";");
			code.add(indent + "fail_assertion(" + stmt.str + ");");
			code.add(indent + c_jump + ":;");
		}
		private void cvt_stmt_return(Parser.ReturnStmt stmt) throws C_Exception {
			String c_name = cvt_expr(stmt.expr);
			code.add(indent + "return " + c_name + ";");
		}


		/*
			CONVERT EXPRESSIONS. Each returns expression output variable name
		 */
		private String cvt_expr(Parser.Expr expr) throws C_Exception {
			if(expr instanceof Parser.TrueExpr)
				return cvt_expr_true((Parser.TrueExpr)expr);
			else if(expr instanceof Parser.FalseExpr)
				return cvt_expr_false((Parser.FalseExpr)expr);
			else if(expr instanceof Parser.IntExpr)
				return cvt_expr_int((Parser.IntExpr)expr);
			else if(expr instanceof Parser.FloatExpr)
				return cvt_expr_float((Parser.FloatExpr)expr);
			else if(expr instanceof Parser.VoidExpr)
				return cvt_expr_void((Parser.VoidExpr)expr);
			else if(expr instanceof Parser.VarExpr)
				return cvt_expr_var((Parser.VarExpr)expr);
			else if(expr instanceof Parser.UnopExpr)
				return cvt_expr_unop((Parser.UnopExpr)expr);
			else if(expr instanceof Parser.BinopExpr)
				return cvt_expr_binop((Parser.BinopExpr)expr);
			else if(expr instanceof Parser.BinopExpr)
				return cvt_expr_binop((Parser.BinopExpr)expr);
			else if(expr instanceof Parser.StructLiteralExpr)
				return cvt_expr_structliteral((Parser.StructLiteralExpr)expr);
			else if(expr instanceof Parser.ArrayLiteralExpr)
				return cvt_expr_arrayliteral((Parser.ArrayLiteralExpr)expr);
			else if(expr instanceof Parser.DotExpr)
				return cvt_expr_dot((Parser.DotExpr)expr);
			else if(expr instanceof Parser.DotExpr)
				return cvt_expr_dot((Parser.DotExpr)expr);
			else if(expr instanceof Parser.ArrayIndexExpr)
				return cvt_expr_arrayIndex((Parser.ArrayIndexExpr)expr);
			else if(expr instanceof Parser.IfExpr)
				return cvt_expr_if((Parser.IfExpr)expr);
			else if(expr instanceof Parser.CallExpr)
				return cvt_expr_call((Parser.CallExpr)expr);
			else if(expr instanceof Parser.SumLoopExpr)
				return cvt_expr_sumloop((Parser.SumLoopExpr)expr);
			else if(expr instanceof Parser.ArrayLoopExpr)
				return cvt_expr_arrayloop((Parser.ArrayLoopExpr)expr);
			else throw new C_Exception("Unknown exception: expression type not known");
		}


		private String cvt_expr_true(Parser.TrueExpr expr) {
			String c_name = gensym();
			code.add(indent + "bool " + c_name + " = true;");
			return c_name;
		}
		private String cvt_expr_false(Parser.FalseExpr expr) {
			String c_name = gensym();
			code.add(indent + "bool " + c_name + " = false;");
			return c_name;
		}
		private String cvt_expr_int(Parser.IntExpr expr) {
			String c_name = gensym();
			code.add(indent + "int64_t " + c_name + " = " + expr.integer + ";");
			return c_name;
		}
		private String cvt_expr_float(Parser.FloatExpr expr) {
			String c_name = gensym();
			code.add(indent + "double " + c_name + " = " + (int)expr.float_val + ".0;"); // truncate to match autograder
			return c_name;
		}
		private String cvt_expr_void(Parser.VoidExpr expr) {
			String c_name = gensym();
			code.add(indent + "void_t " + c_name + " = {};");
			return c_name;
		}
		private String cvt_expr_var(Parser.VarExpr expr) {
			return p_name_map.get(expr.str);
		}
		private String cvt_expr_unop(Parser.UnopExpr expr) throws C_Exception {
			String c_name_inner = cvt_expr(expr.expr);
			String c_name = gensym();
			code.add(indent + get_c_type(expr) + " " + c_name + " = " + expr.op + c_name_inner + ";");
			return c_name;
		}
		private String cvt_expr_binop(Parser.BinopExpr expr) throws C_Exception {
			if(expr.op.equals("&&"))
				return cvt_expr_and(expr);
			if(expr.op.equals("||"))
				return cvt_expr_or(expr);

			String c_name1 = cvt_expr(expr.expr1);
			String c_name2 = cvt_expr(expr.expr2);
			String c_name = gensym();
			if(expr.op.equals("%") && expr.type.type_name.equals("FloatType"))
				code.add(indent + get_c_type(expr) + " " + c_name + " = fmod(" + c_name1 + ", " + c_name2 + ");");
			else
				code.add(indent + get_c_type(expr) + " " + c_name + " = " + c_name1 + " " + expr.op + " " + c_name2 + ";");
			return c_name;
		}
		private String cvt_expr_and(Parser.BinopExpr expr) throws C_Exception {
			String c_name = gensym();
			String c_name1 = cvt_expr(expr.expr1);
			code.add(indent + "bool " + c_name + " = " + c_name1 + ";");
			code.add(indent + "if (0 == " + c_name1 + ")");
			String jump = parent.add_jump();
			code.add(indent + "goto " + jump + ";");

			String c_name2 = cvt_expr(expr.expr2);
			code.add(indent + c_name + " = " + c_name2 + ";");
			code.add(indent + jump + ":;");
			return c_name;
		}
		private String cvt_expr_or(Parser.BinopExpr expr) throws C_Exception {
			String c_name = gensym();
			String c_name1 = cvt_expr(expr.expr1);
			code.add(indent + "bool " + c_name + " = " + c_name1 + ";");
			code.add(indent + "if (0 != " + c_name1 + ")");
			String jump = parent.add_jump();
			code.add(indent + "goto " + jump + ";");

			String c_name2 = cvt_expr(expr.expr2);
			code.add(indent + c_name + " = " + c_name2 + ";");
			code.add(indent + jump + ":;");
			return c_name;
		}
		private String cvt_expr_structliteral(Parser.StructLiteralExpr expr) throws C_Exception {
			List<String> c_names = new ArrayList<>();
			for(Parser.Expr e :expr.expressions) {
				c_names.add(cvt_expr(e));
			}
			String c_name = gensym();
			String line = indent + expr.identifier + " " + c_name + " = { ";

			for(String cn : c_names)
				line += cn + ", ";

			if(c_names.size() > 0)
				line = line.substring(0, line.length()-2);
			code.add(line + " };");
			return c_name;
		}
		private String cvt_expr_arrayliteral(Parser.ArrayLiteralExpr expr) throws C_Exception {
			List<String> c_names = new ArrayList<>();
			for(Parser.Expr e :expr.exprs) {
				c_names.add(cvt_expr(e));
			}
			String c_name = gensym();

			// put struct in code if it doesn't already exist
			String c_type = get_c_type(expr.exprs.get(0));
			String struct_name = parent.add_struct(1, c_type);

			code.add(indent + struct_name + " " + c_name + ";");
			code.add(indent + c_name + ".d0 = " + c_names.size() + ";");
			code.add(indent + c_name + ".data = jpl_alloc(sizeof(" + c_type + ") * " + c_names.size() + ");");
			for(int i = 0; i < c_names.size(); i++)
				code.add(indent + c_name + ".data[" + i + "] = " + c_names.get(i) + ";");

			return c_name;
		}
		private String cvt_expr_dot(Parser.DotExpr expr) throws C_Exception {
			String c_name_inner = cvt_expr(expr.expr);
			String c_name = gensym();

			//parent.struct_more_info.get()
			String c_type = get_c_type(expr.type);
			code.add(indent + c_type + " " + c_name + " = " + c_name_inner + "." + expr.str + ";");
			return c_name;
		}
		private String cvt_expr_arrayIndex(Parser.ArrayIndexExpr expr) throws C_Exception {
			String c_name_outer = cvt_expr(expr.expr);
			List<String> c_names = new ArrayList<>();
			// convert inner expressions
			for(var ie : expr.expressions) // seems to only let 1 index here in staff compiler
				c_names.add(cvt_expr(ie));

			for(int i = 0; i < c_names.size(); i++) {
				code.add(indent + "if (" + c_names.get(i) + " >= 0)");
				String c_jump = parent.add_jump();
				code.add(indent + "goto " + c_jump + ";");
				code.add(indent + "fail_assertion(\"negative array index\");");
				code.add(indent + c_jump + ":;");

				code.add(indent + "if (" + c_names.get(i) + " < " + c_name_outer + ".d" + i + ")");
				String c_jump2 = parent.add_jump();
				code.add(indent + "goto " + c_jump2 + ";");
				code.add(indent + "fail_assertion(\"index too large\");");
				code.add(indent + c_jump2 + ":;");
			}

			String i_name = gensym();
			code.add(indent + "int64_t " + i_name + " = 0;");
			for(int i = 0; i < c_names.size(); i++) {
				code.add(indent + i_name + " *= " + c_name_outer + ".d" + i + ";");
				code.add(indent + i_name + " += " + c_names.get(i) + ";");
			}

			String c_name = gensym();
			String c_type = get_c_type(expr.type);
			code.add(indent + c_type + " " + c_name + " = " + c_name_outer + ".data[" + i_name + "];");

			return c_name;
		}
		private String cvt_expr_if(Parser.IfExpr expr) throws C_Exception {
			String c_name1 = cvt_expr(expr.expr1);
			String c_type = get_c_type(expr.expr2);
			String c_name = gensym();

			code.add(indent + c_type + " " + c_name + ";");
			code.add(indent + "if (!" + c_name1 + ")");
			String c_jump1 = parent.add_jump();
			code.add(indent + "goto " + c_jump1 + ";");

			String c_name2 = cvt_expr(expr.expr2);
			code.add(indent + c_name + " = " + c_name2 + ";");
			String c_jump2 = parent.add_jump();
			code.add(indent + "goto " + c_jump2 + ";");

			code.add(indent + c_jump1 + ":;");
			String c_name3 = cvt_expr(expr.expr3);
			code.add(indent + c_name + " = " + c_name3 + ";");

			code.add(indent + c_jump2 + ":;");

			return c_name;
		}
		private String cvt_expr_call(Parser.CallExpr expr) throws C_Exception {
			List<String> c_names = new ArrayList<>();
			for(var argument : expr.expressions)
				c_names.add(cvt_expr(argument));

			// need return type
			String c_type;
			try {
				TypeChecker.FunctionInfo fi =(TypeChecker.FunctionInfo) parent.env.lookup(expr.identifier);
				c_type =  get_c_type(fi.return_type);
			}
			catch(Exception e) {
				throw new C_Exception(e.toString());
			}

			// generate c input string
			String inputs = "";
			for(String cn : c_names)
				inputs += cn + ", ";
			if(c_names.size() > 0)
				inputs = inputs.substring(0, inputs.length()-2);

			String c_name = gensym();
			code.add(indent + c_type + " " + c_name + " = " + expr.identifier + "(" + inputs + ");" );
			return c_name;
		}
		private String cvt_expr_sumloop(Parser.SumLoopExpr expr) throws C_Exception {
			String c_name = gensym();
			String c_type = get_c_type(expr.expr);
			code.add(indent + c_type + " " + c_name + ";");

			List<String> c_names = new ArrayList<>();
			for(int i = 0; i < expr.variables.size(); i++) {
				code.add(indent + "// Computing bound for " + expr.variables.get(i));
				String c_namei = cvt_expr(expr.expressions.get(i));
				c_names.add(c_namei);

				code.add(indent + "if (" + c_namei + " > 0) ");
				String c_jump = parent.add_jump();
				code.add(indent + "goto " + c_jump + ";");
				code.add(indent + "fail_assertion(\"non-positive loop bound\");");
				code.add(indent + c_jump + ":;");
			}

			code.add(indent + c_name + " = 0;");
			LinkedList<String> c_varnames = new LinkedList<>();
			for(int i = expr.variables.size()-1; i >= 0; i--) {
				String c_varname = gensym();
				c_varnames.addFirst(c_varname);
				code.add(indent + "int64_t " + c_varname + " = 0; // " + expr.variables.get(i));

				// save variable bindings
				p_name_map.put(expr.variables.get(i), c_varname);
				parent.expr_map.put(c_varname, expr.expressions.get(i));
			}
			String c_jump_body = parent.add_jump();
			code.add(indent + c_jump_body + ":; // Begin body of loop");

			String body_c_name = cvt_expr(expr.expr);
			code.add(indent + c_name + " += " + body_c_name + ";");

			for(int i = expr.variables.size()-1; i >= 0; i--) {
				code.add(indent + c_varnames.get(i) + "++;");
				code.add(indent + "if (" + c_varnames.get(i) + " < " + c_names.get(i) + ")");
				code.add(indent + "goto " + c_jump_body + ";");
				code.add(indent + c_varnames.get(i) + " = 0;"); // remove this on last iteration
			}
			code.remove(code.size()-1);
			code.add(indent + "// End body of loop");

			return c_name;
		}
		private String cvt_expr_arrayloop(Parser.ArrayLoopExpr expr) throws C_Exception {
			String c_name = gensym();
			String c_type = get_c_type(expr.expr);
			String st_name = "_a" + expr.expressions.size() + "_" + c_type;
			code.add(indent + st_name + " " + c_name + ";");

			List<String> c_names = new ArrayList<>();
			for(int i = 0; i < expr.variables.size(); i++) {
				code.add(indent + "// Computing bound for " + expr.variables.get(i));
				String c_namei = cvt_expr(expr.expressions.get(i));
				c_names.add(c_namei);

				code.add(indent + c_name + ".d" + i + " = " + c_namei + ";");
				code.add(indent + "if (" + c_namei + " > 0) ");
				String c_jump = parent.add_jump();
				code.add(indent + "goto " + c_jump + ";");
				code.add(indent + "fail_assertion(\"non-positive loop bound\");");
				code.add(indent + c_jump + ":;");
			}

			code.add(indent + "// Computing total size of heap memory to allocate");
			String size_var = gensym();
			code.add(indent + "int64_t " + size_var + " = 1;");
			for(String cn : c_names) {
				code.add(indent + size_var + " *= " + cn + ";");
			}
			code.add(indent + size_var + " *= sizeof(" + c_type + ");");
			code.add(indent + c_name + ".data = jpl_alloc(" + size_var + ");");

			LinkedList<String> c_varnames = new LinkedList<>();
			for(int i = expr.variables.size()-1; i >= 0; i--) {
				String c_varname = gensym();
				c_varnames.addFirst(c_varname);
				code.add(indent + "int64_t " + c_varname + " = 0; // " + expr.variables.get(i));

				// save variable bindings
				p_name_map.put(expr.variables.get(i), c_varname);
				parent.expr_map.put(c_varname, expr.expressions.get(i));
			}
			String c_jump_body = parent.add_jump();
			code.add(indent + c_jump_body + ":; // Begin body of loop");

			String body_c_name = cvt_expr(expr.expr); // adds _a1_int64
			String idx = gensym();
			code.add(indent + "int64_t " + idx + " = 0;");
			for(int i = 0; i < expr.variables.size(); i++) {
				code.add(indent + idx + " *= " + c_name + ".d" + i + ";");
				code.add(indent + idx + " += " + c_varnames.get(i) + ";");
			}
			code.add(indent + c_name + ".data[" + idx + "] = " + body_c_name + ";");

			parent.add_struct(expr.expressions.size(), c_type); // adds _a3__a1_int64_t;

			for(int i = expr.variables.size()-1; i >= 0; i--) {
				code.add(indent + c_varnames.get(i) + "++;");
				code.add(indent + "if (" + c_varnames.get(i) + " < " + c_names.get(i) + ")");
				code.add(indent + "goto " + c_jump_body + ";");
				code.add(indent + c_varnames.get(i) + " = 0;"); // remove this on last iteration
			}
			code.remove(code.size()-1);
			code.add(indent + "// End body of loop");

			return c_name;

		}
	}
}

class TypeChecker {

	// returns ctx
	public static Environment type_check(List<Parser.ASTNode> commands) throws TypeException {
		try {
			Environment env = create_start_environment();
			for(Parser.ASTNode cmd : commands) {
				type_cmd(cmd, env);
			}
			return env;
		}
		catch (Exception e) {
			System.out.println("Compilation failed\n");
			throw new TypeException("Caught Exception: " + e.toString());
		}
	}

	private static Environment type_cmd(Parser.ASTNode cmd, Environment env) throws TypeException {

		if(cmd instanceof Parser.StructCmd) {
			new StructInfo((Parser.StructCmd)cmd, env); // adds itself into env
		}
		else if(cmd instanceof Parser.ShowCmd) {
			type_of(((Parser.ShowCmd)cmd).expr, env); // puts TypeValue into expr recursively
		}
		else if(cmd instanceof Parser.LetCmd) {
			Parser.LetCmd letcmd = (Parser.LetCmd)cmd;
			TypeValue expr_type = type_of(letcmd.expr, env);
			env.add_lvalue(letcmd.lvalue, expr_type);
		}
		else if(cmd instanceof Parser.WriteCmd) {
			Parser.WriteCmd writecmd = (Parser.WriteCmd)cmd;
			TypeValue tv = type_of(writecmd.expr, env);
			// must be array rank 2 of rgba values
			if(!(tv instanceof ArrayType))
				throw new TypeException("Write command expression needs to be an array of rgba values rank 2");
			ArrayType arr_type = (ArrayType)tv;
			if(!(arr_type.inner_type instanceof StructType) || arr_type.rank != 2)
				throw new TypeException("Write command expression needs to be an array of rgba values rank 2");
			StructType inner_type = (StructType)arr_type.inner_type;
			if(!inner_type.struct_name.equals("rgba"))
				throw new TypeException("Write command expression needs to be an array of rgba values rank 2");
		}
		else if(cmd instanceof Parser.ReadCmd) {
			Parser.ReadCmd readcmd = (Parser.ReadCmd)cmd;
			StructType innerType = new StructType("rgba"); // type of array
			ArrayType arr_type =  new ArrayType(2, innerType);
			env.add_lvalue(readcmd.lvalue, arr_type);

			//ValueInfo info = new ValueInfo(readcmd.lvalue.identifier, arr_type);
			//env.add(readcmd.lvalue.identifier, info);
		}
		else if(cmd instanceof Parser.AssertCmd) {
			Parser.AssertCmd assertcmd = (Parser.AssertCmd)cmd;
			TypeValue tv = type_of(assertcmd.expr, env);
			if(!tv.type_name.equals("BoolType"))
				throw new TypeException("Assert condition must be a bool");
		}
		else if(cmd instanceof Parser.FnCmd) {
			handle_fn_cmd((Parser.FnCmd)cmd, env);
		}
		else if(cmd instanceof Parser.TimeCmd) {
			Parser.TimeCmd timecmd = (Parser.TimeCmd)cmd;
			type_cmd(timecmd.cmd, env);
		}
		else if(cmd instanceof Parser.PrintCmd) {
			// do nothing
		}
		else throw new TypeException("Command not found");


		return env;
	}

	private static Environment create_start_environment() throws TypeException {
		Environment env = new Environment();
		// add rgba struct
		env.add("rgba", StructInfo.GetRGBA());

		// add built-in functions
		env.add("sqrt", FunctionInfo.GetSqrt());
		env.add("exp", FunctionInfo.GetExp());
		env.add("sin", FunctionInfo.GetSin());
		env.add("cos", FunctionInfo.GetCos());
		env.add("tan", FunctionInfo.GetTan());
		env.add("asin", FunctionInfo.GetAsin());
		env.add("acos", FunctionInfo.GetAcos());
		env.add("atan", FunctionInfo.GetATan());
		env.add("log", FunctionInfo.GetLog());
		env.add("pow", FunctionInfo.GetPow());
		env.add("atan2", FunctionInfo.GetAtan2());
		env.add("to_float", FunctionInfo.GetToFloat());
		env.add("to_int", FunctionInfo.GetToInt());

		// add built-in variables
		env.add("args", new ValueInfo("args", new ArrayType(1, new TypeValue("IntType"))));
		env.add("argnum", new ValueInfo("argnum", new TypeValue("IntType")));

		return env;
	}

	private static Environment handle_fn_cmd(Parser.FnCmd cmd, Environment env) throws TypeException {
		Environment child = new Environment();
		child.parent = env;

		// get argument types
		List<TypeValue> arg_types = new ArrayList<>();
		for(Parser.Binding bind : cmd.bindings) {
			Parser.LValue lvalue = bind.lvalue;
			Parser.Type p_type = bind.type;
			TypeValue t_type = getType(p_type);
			child.add_lvalue(lvalue, t_type);
			arg_types.add(t_type);
		}

		// get return type
		TypeValue ret_type = getType(cmd.return_value);

		// construct FunctionInfo
		FunctionInfo info = new FunctionInfo(cmd.identifier, ret_type, arg_types, child);
		env.add(cmd.identifier, info);

		// type check statements
		return type_stmt(cmd.statements, child, ret_type);
	}

	private static Environment type_stmt(List<Parser.Stmt> statements, Environment env, TypeValue ret_type) throws TypeException {
		boolean has_return = false;
		for(Parser.Stmt stmt : statements) {
			if (stmt instanceof Parser.LetStmt) {
				Parser.LetStmt letstmt = (Parser.LetStmt)stmt;
				TypeValue expr_type = type_of(letstmt.expr, env);
				env.add_lvalue(letstmt.lvalue, expr_type);
			} else if (stmt instanceof Parser.AssertStmt) {
				Parser.AssertStmt assertstmt = (Parser.AssertStmt)stmt;
				TypeValue tv = type_of(assertstmt.expr, env);
				if(!tv.type_name.equals("BoolType"))
					throw new TypeException("Assert condition must be a bool");
			} else if (stmt instanceof Parser.ReturnStmt) {
				has_return = true;
				Parser.ReturnStmt returnstmt = (Parser.ReturnStmt)stmt;
				TypeValue tv = type_of(returnstmt.expr, env);
				if(!tv.toString().equals(ret_type.toString()))
					throw new TypeException("Return type for function is incorrect");
			}
		}
		if(!has_return && !ret_type.type_name.equals("VoidType"))
			throw new TypeException("Return required of type " + ret_type.toString());


		return env;
	}




	// NOTE: the way the code here is written makes it so that one TypeValue object may be shared
	// between many Exprs. If any issues arise it may be due to this shared state
	public static class TypeValue {
		String type_name;

		public TypeValue(String type_name) {
			this.type_name = type_name;
		}

		@Override
		public String toString() {
			return "(" + type_name + ")";
		}
	}
	public static class StructType extends TypeValue {
		String struct_name;

		public StructType(String struct_name) {
			super("StructType");
			this.struct_name = struct_name;
		}

		@Override
		public String toString() {
			return "(StructType " + struct_name + ")";
		}
	}
	public static class ArrayType extends TypeValue {
		int rank; // seems to always be 1
		TypeValue inner_type;

		public ArrayType(int rank, TypeValue inner_type) {
			super("ArrayType");
			this.rank = rank;
			this.inner_type = inner_type;
		}

		@Override
		public String toString() {
			return "(ArrayType " + inner_type.toString() + " " + rank + ")";
		}
	}

	private static class TypeException extends Exception {

		private String msg = "Compilation failed\n";

		public TypeException(String msg) {
			this.msg += "Type error: " + msg;
		}

		@Override
		public String toString() {
			return msg;
		}
	}


	public static class Environment {
		private Environment parent;
		private HashMap<String, NameInfo> ctx = new HashMap<>();

		// checks this scope and all parent scopes. Throws error if name not in scope
		public NameInfo lookup(String name) throws TypeException {
			NameInfo info = lookup_internal(name);
			if(info == null)
				throw new TypeException("Symbol " + name + " is not in scope");
			return info;
		}

		private NameInfo lookup_internal(String name) {
			if(ctx.containsKey(name))
				return ctx.get(name);

			if(parent == null)
				return null;

			return parent.lookup_internal(name);
		}

		// add name to this context
		public void add(String name, NameInfo info) throws TypeException {
			// if name is already in scope
			if(lookup_internal(name) != null)
				throw new TypeException("Symbol " + name + " is already in use");

			ctx.put(name, info);
		}

		// like lookup but does not throw
		public boolean has(String name) {
			NameInfo info = lookup_internal(name);
			if(info == null)
				return false;
			else return true;
		}

		public void add_lvalue(Parser.LValue lvalue, TypeValue type) throws TypeException {
			if(lvalue instanceof Parser.VarLValue) {
				Parser.VarLValue varlvalue = (Parser.VarLValue)lvalue;
				add(varlvalue.identifier, new ValueInfo(varlvalue.identifier, type));
			}
			else if(lvalue instanceof Parser.ArrayLValue) {
				Parser.ArrayLValue arrlvalue = (Parser.ArrayLValue)lvalue;

				// bind ints
				ArrayType arr_type = (ArrayType)type;
				if(arrlvalue.variables.size() != arr_type.rank)
					throw new TypeException("cannot bind array to incorrect rank");
				for(String str : arrlvalue.variables)
					add(str, new ValueInfo(str, new TypeValue("IntType")));

				// bind array's type
				add(arrlvalue.identifier, new ValueInfo(arrlvalue.identifier, type));
			}
			else throw new TypeException("Unknown lvalue type");
		}

	}
	public static class NameInfo {
		String name;
	}
	public static class ValueInfo extends NameInfo {
		TypeValue type;

		public ValueInfo(String name, TypeValue type) {
			this.name = name;
			this.type = type;
		}
	}
	public static class StructInfo extends NameInfo {
		List<String> var_names;
		List<TypeValue> types;

		private StructInfo(String name, List<String> var_names, List<TypeValue> types) {
			this.name = name;
			this.var_names = var_names;
			this.types = types;
		}

		public StructInfo(Parser.StructCmd struct_cmd, Environment env) throws TypeException {
			this.name = struct_cmd.identifier;
			this.var_names = struct_cmd.variables;

			// make sure no duplicate var names
			if(new HashSet<>(var_names).size() != var_names.size())
				throw new TypeException("Struct has duplicate identifiers");

			// make sure these inner types are in scope
			for(Parser.Type t : struct_cmd.types) {
				if(t instanceof Parser.StructType) {
					// verify this struct name inside was already declared
					String struct_name = ((Parser.StructType)t).struct_name;
					if(!env.has(struct_name))
						throw new TypeException("Struct " + struct_name + " has not been declared");
				}
				else if(t instanceof Parser.ArrayType) { // check same for arrays of structs
					Parser.Type inner_type = ((Parser.ArrayType)t).type;
					if(inner_type instanceof Parser.StructType) {
						// verify this struct name inside was already declared
						String struct_name = ((Parser.StructType)inner_type).struct_name;
						if(!env.has(struct_name))
							throw new TypeException("Struct " + struct_name + " has not been declared");
					}
				}
			}

			// create list of types
			types = new ArrayList<>();
			for(Parser.Type t : struct_cmd.types)
				types.add(getType(t));

			// checks for duplicate struct name and adds to environment
			env.add(name, this);
		}

		public static StructInfo GetRGBA() {
			String name = "rgba";
			List<String> var_names = new ArrayList<>();
			var_names.add("r");
			var_names.add("g");
			var_names.add("b");
			var_names.add("a");
			List<TypeValue> types = new ArrayList<>();
			types.add(new TypeValue("FloatType"));
			types.add(new TypeValue("FloatType"));
			types.add(new TypeValue("FloatType"));
			types.add(new TypeValue("FloatType"));
			return new StructInfo(name, var_names, types);
		}

	}
	public static class FunctionInfo extends NameInfo {
		TypeValue return_type;
		List<TypeValue> argument_types;
		Environment env;

		public FunctionInfo(String name, TypeValue return_type, List<TypeValue> argument_types, Environment env) {
			this.name = name;
			this.return_type = return_type;
			this.argument_types = argument_types;
			this.env = env;
		}

		public static FunctionInfo GetSqrt() {
			String name = "sqrt";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetExp() {
			String name = "exp";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetSin() {
			String name = "sin";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetCos() {
			String name = "cos";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetTan() {
			String name = "tan";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetAsin() {
			String name = "asin";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetAcos() {
			String name = "acos";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetATan() {
			String name = "atan";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetLog() {
			String name = "log";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetPow() {
			String name = "pow";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetAtan2() {
			String name = "atan2";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetToFloat() {
			String name = "to_float";
			TypeValue return_type = new TypeValue("FloatType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("IntType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
		public static FunctionInfo GetToInt() {
			String name = "to_int";
			TypeValue return_type = new TypeValue("IntType");
			List<TypeValue> arg_types = new ArrayList<>();
			arg_types.add(new TypeValue("FloatType"));
			return new FunctionInfo(name, return_type, arg_types, null);
		}
	}

	// convert from parser type to TypeValue
	private static TypeValue getType(Parser.Type type) throws TypeException {
		if(type instanceof Parser.IntType)
			return new TypeValue("IntType");
		else if(type instanceof Parser.FloatType)
			return new TypeValue("FloatType");
		else if(type instanceof Parser.BoolType)
			return new TypeValue("BoolType");
		else if(type instanceof Parser.VoidType)
			return new TypeValue("VoidType");
		else if(type instanceof Parser.StructType)
			return new StructType(((Parser.StructType)type).struct_name);
		else if(type instanceof Parser.ArrayType)
			return new ArrayType(((Parser.ArrayType)type).dimension, getType(((Parser.ArrayType)type).type));
		else throw new TypeException("Unknown type, unknown error");
	}



	// puts expression's type inside expression. Returns that expression type
	private static TypeValue type_of(Parser.Expr expr, Environment env) throws TypeException {
		TypeValue tv;
		if(expr instanceof Parser.IntExpr)
			tv = new TypeValue("IntType");
		else if(expr instanceof Parser.FloatExpr)
			tv = new TypeValue("FloatType");
		else if(expr instanceof Parser.TrueExpr || expr instanceof Parser.FalseExpr)
			tv = new TypeValue("BoolType");
		else if(expr instanceof Parser.VoidExpr)
			tv = new TypeValue("VoidType");
		else if(expr instanceof Parser.VarExpr)
			tv = type_check_varexpr((Parser.VarExpr)expr, env);
		else if(expr instanceof Parser.UnopExpr)
			tv = type_check_unary((Parser.UnopExpr)expr, env);
		else if(expr instanceof Parser.BinopExpr)
			tv = type_check_binary((Parser.BinopExpr)expr, env);
		else if(expr instanceof Parser.StructLiteralExpr)
			tv = type_check_struct((Parser.StructLiteralExpr)expr, env);
		else if(expr instanceof Parser.ArrayLiteralExpr)
			tv = type_check_array((Parser.ArrayLiteralExpr)expr, env);
		else if(expr instanceof Parser.IfExpr)
			tv = type_check_ifexpr((Parser.IfExpr)expr, env);
		else if(expr instanceof Parser.DotExpr)
			tv = type_check_dotexpr((Parser.DotExpr)expr, env);
		else if(expr instanceof Parser.ArrayIndexExpr)
			tv = type_check_arrayindex((Parser.ArrayIndexExpr)expr, env);
		else if(expr instanceof Parser.SumLoopExpr)
			tv = type_check_sumloopexpr((Parser.SumLoopExpr)expr, env);
		else if(expr instanceof Parser.ArrayLoopExpr)
			tv = type_check_arrayloopexpr((Parser.ArrayLoopExpr)expr, env);
		else if(expr instanceof Parser.CallExpr)
			tv = type_check_callexpr((Parser.CallExpr)expr, env);
		else throw new TypeException("Unknown Expression found when type checking");

		expr.type = tv;
		return tv;
	}

	private static TypeValue type_check_unary(Parser.UnopExpr unop, Environment env) throws TypeException {
		TypeValue unop_type = type_of(unop.expr, env);
		String type_name = unop_type.type_name;

		if(unop.op.equals("!") && type_name.equals("BoolType"))
			return new TypeValue("BoolType");
		else if(unop.op.equals("-") && type_name.equals("IntType"))
			return new TypeValue("IntType");
		else if(unop.op.equals("-") && type_name.equals("FloatType"))
			return new TypeValue("FloatType");
		else throw new TypeException("Incorrect type after " + unop.op + " at byte " + unop.start_byte);
	}

	private static TypeValue type_check_binary(Parser.BinopExpr binop, Environment env) throws TypeException {
		TypeValue t1 = type_of(binop.expr1, env);
		TypeValue t2 = type_of(binop.expr2, env);

		// make sure t1 and t2 are the same type
		if(!t1.toString().equals(t2.toString()))
			throw new TypeException("Types don't match at binary expression at " + binop.start_byte);

		if((binop.op.equals("+") || binop.op.equals("-") || binop.op.equals("*") || binop.op.equals("/") || binop.op.equals("%"))
				&& (t1.type_name.equals("IntType") || t1.type_name.equals("FloatType")))
			return new TypeValue(t1.type_name); // either IntType or FloatType
		else if((binop.op.equals("<") || binop.op.equals("<=") || binop.op.equals(">") || binop.op.equals(">="))
				&& (t1.type_name.equals("IntType") || t1.type_name.equals("FloatType")))
			return new TypeValue("BoolType");
		else if((binop.op.equals("&&") || binop.op.equals("||")) && t1.type_name.equals("BoolType"))
			return new TypeValue("BoolType");
		else if(binop.op.equals("==") || binop.op.equals("!="))
			return new TypeValue("BoolType");
		else throw new TypeException("Incorrect types with " + binop.op + " expression at byte " + binop.start_byte);
	}

	private static StructType type_check_struct(Parser.StructLiteralExpr expr, Environment env) throws TypeException {
		StructInfo info = (StructInfo)env.lookup(expr.identifier);

		// check that the types/order are correct
		if(info.types.size() != expr.expressions.size())
			throw new TypeException("Incorrect amount of expressions at " + expr.start_byte);

		for(int i = 0; i < info.types.size(); i++) {
			Parser.Expr val = expr.expressions.get(i);
			TypeValue actual_type = type_of(val, env);
			TypeValue required_type = info.types.get(i);

			if(!actual_type.toString().equals(required_type.toString()))
				throw new TypeException("Incorrect type put into struct literal expression at " + val.start_byte);
		}

		return new StructType(expr.identifier);
	}

	private static ArrayType type_check_array(Parser.ArrayLiteralExpr expr, Environment env) throws TypeException {
		// check for empty array constructor []
		if(expr.exprs.size() == 0)
			throw new TypeException("Empty array constructors are not allowed at " + expr.start_byte);

		// check that the types are all the same
		TypeValue type = type_of(expr.exprs.get(0), env);
		for(int i = 1; i < expr.exprs.size(); i++) {
			TypeValue t = type_of(expr.exprs.get(i), env);
			if(!type.toString().equals(t.toString()))
				throw new TypeException("Array literal types do not match at " + expr.start_byte);
		}

		return new ArrayType(1, type);
	}

	private static TypeValue type_check_ifexpr(Parser.IfExpr expr, Environment env) throws TypeException {
		TypeValue condition_type = type_of(expr.expr1, env);
		if(!condition_type.type_name.equals("BoolType"))
			throw new TypeException("Bool condition required at " + expr.start_byte);

		TypeValue then_type = type_of(expr.expr2, env);
		TypeValue else_type = type_of(expr.expr3, env);
		if(!then_type.toString().equals(else_type.toString()))
			throw new TypeException("Then and Else types must match at " + expr.start_byte);

		return then_type;
	}

	private static TypeValue type_check_dotexpr(Parser.DotExpr expr, Environment env) throws TypeException {
		TypeValue before_dot = type_of(expr.expr, env);
		if(!(before_dot instanceof StructType))
			throw new TypeException("Dot expression must have Struct type before dot at " + expr.start_byte);

		// get correct type for this
		StructType st = (StructType)before_dot;
		StructInfo info = (StructInfo)env.lookup(st.struct_name);

		for(int i = 0; i < info.var_names.size(); i++) {
			// if identifier is found in this struct info
			if(expr.str.equals(info.var_names.get(i))) {
				// return the corresponding type
				return info.types.get(i);
			}
		}
		throw new TypeException("Variable does not exist in struct at byte " + expr.start_byte);

	}

	private static TypeValue type_check_arrayindex(Parser.ArrayIndexExpr expr, Environment env) throws TypeException {
		TypeValue to_index = type_of(expr.expr, env);
		if(!(to_index instanceof ArrayType))
			throw new TypeException("Array index expression must have array type before at " + expr.start_byte);

		// make sure 'rank' expressions are ints
		for(Parser.Expr e : expr.expressions) {
			TypeValue integer = type_of(e, env);
			if(!integer.type_name.equals("IntType"))
				throw new TypeException("Array indexing can only use integers at " + expr.start_byte);
		}

		// get correct type for this
		ArrayType at = (ArrayType) to_index;

		// make sure rank of array is the same as # of indices in index
		if(at.rank != expr.expressions.size())
			throw new TypeException("Array expressions need the correct amount of indices");

		return at.inner_type;
	}

	private static TypeValue type_check_varexpr(Parser.VarExpr expr, Environment env) throws TypeException {
		ValueInfo info = (ValueInfo)env.lookup(expr.str);
		return info.type;
	}

	private static TypeValue type_check_sumloopexpr(Parser.SumLoopExpr sum_expr, Environment env) throws TypeException {
		if(sum_expr.expressions.size() == 0)
			throw new TypeException("Sum loop Expressions must have bindings");

		// loop bounds / bindings need to be integers
		for(Parser.Expr expr : sum_expr.expressions) {
			TypeValue tv = type_of(expr, env);
			if(!tv.type_name.equals("IntType"))
				throw new TypeException("Sum loop bindings must be integers");
		}

		// add variables to new environment
		Environment child = new Environment();
		child.parent = env;
		for(int i = 0; i < sum_expr.variables.size(); i++) {
			String name = sum_expr.variables.get(i);
			TypeValue tv = sum_expr.expressions.get(i).type;
			ValueInfo info = new ValueInfo(name, tv);
			child.add(name, info);
		}

		TypeValue sum_body_type = type_of(sum_expr.expr, child);

		// must be int or float
		if(!(sum_body_type.type_name.equals("IntType") || sum_body_type.type_name.equals("FloatType")))
			throw new TypeException("Sum loop body expression must evaluate to int or float");

		return sum_body_type;
	}

	private static TypeValue type_check_arrayloopexpr(Parser.ArrayLoopExpr arr_expr, Environment env) throws TypeException {
		if(arr_expr.expressions.size() == 0)
			throw new TypeException("Array loop Expressions must have bindings");

		// loop bounds / bindings need to be integers
		for(Parser.Expr expr : arr_expr.expressions) {
			TypeValue tv = type_of(expr, env);
			if(!tv.type_name.equals("IntType"))
				throw new TypeException("Array loop bindings must be integers");
		}

		// add variables to new environment
		Environment child = new Environment();
		child.parent = env;
		for(int i = 0; i < arr_expr.variables.size(); i++) {
			String name = arr_expr.variables.get(i);
			TypeValue tv = arr_expr.expressions.get(i).type;
			ValueInfo info = new ValueInfo(name, tv);
			child.add(name, info);
		}

		TypeValue arr_inner_type = type_of(arr_expr.expr, child);
		ArrayType arr_type = new ArrayType(arr_expr.variables.size(), arr_inner_type);
		return arr_type;
	}

	private static TypeValue type_check_callexpr(Parser.CallExpr call_expr, Environment env) throws TypeException {
		// type check all function arguments
		List<TypeValue> parm_types = new ArrayList<>();
		for(Parser.Expr expr : call_expr.expressions)
			parm_types.add(type_of(expr, env));

		// look up function name, check that it is a function type
		NameInfo info = env.lookup(call_expr.identifier);
		if(!(info instanceof FunctionInfo))
			throw new TypeException("Using a non function to initiate a call expression");
		FunctionInfo f_info = (FunctionInfo)info;

		// verify argument types
		if(f_info.argument_types.size() != parm_types.size())
			throw new TypeException("Call expression arguments list does not match size of param list");
		for(int i = 0; i < f_info.argument_types.size(); i++) {
			TypeValue arg = f_info.argument_types.get(i);
			TypeValue parm = parm_types.get(i);
			if(!arg.toString().equals(parm.toString()))
				throw new TypeException("Call expression arguments list does not match types of param list");
		}

		return f_info.return_type;
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

	public static class Cmd extends ASTNode {}
	public static class Expr extends ASTNode {
		TypeChecker.TypeValue type;

		protected String getType() {
			if(type == null)
				return "";
			else
				return type.toString() + " ";
		}
	}
	public static class Type extends ASTNode {}
	public static class Stmt extends ASTNode {}
	public static class LValue extends ASTNode {
		public String identifier;
	}
	public static class Binding extends ASTNode{
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

	public static class VarLValue extends LValue {
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
	public static class ArrayLValue extends LValue {
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

	public static class LetStmt extends Stmt {
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
	public static class AssertStmt extends Stmt {
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
	public static class ReturnStmt extends Stmt {
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

	public static class ReadCmd extends Cmd {
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
	public static class WriteCmd extends Cmd {
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
	public static class LetCmd extends Cmd {
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
	public static class AssertCmd extends Cmd {
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
	public static class PrintCmd extends Cmd {
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
	public static class ShowCmd extends Cmd {
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
	public static class TimeCmd extends Cmd {
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
	public static class FnCmd extends Cmd {
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
	public static class StructCmd extends Cmd {
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

	public static class IntExpr extends Expr {
		long integer;

		public IntExpr(int start_byte, int end_pos, long integer) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.integer = integer;
		}

		@Override
		public String toString() {
			return "(IntExpr " + getType() + integer + ")";
		}
	}
	public static class FloatExpr extends Expr {
		double float_val;

		public FloatExpr(int start_byte, int end_pos, double float_val) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.float_val = float_val;
		}

		@Override
		public String toString() {
			return "(FloatExpr " + getType() + (long)float_val + ")";
		}
	}
	public static class TrueExpr extends Expr {
		public TrueExpr(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			if(type != null)
				return "(TrueExpr " + type.toString() + ")";
			else
				return "(TrueExpr)";
		}
	}
	public static class FalseExpr extends Expr {
		public FalseExpr(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			if(type != null)
				return "(FalseExpr " + type.toString() + ")";
			else
				return "(FalseExpr)";
		}
	}
	public static class VarExpr extends Expr {
		String str;

		public VarExpr(int start_byte, int end_pos, String str) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.str = str;
		}

		@Override
		public String toString() {
			if(type != null)
				return "(VarExpr " + type.toString() + " " + str + ")";
			else
				return "(VarExpr " + str + ")";
		}
	}
	public static class ArrayLiteralExpr extends Expr {
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

				return "(ArrayLiteralExpr " + getType() + out.substring(0, out.length()-1) + ")";
			}
		}
	}
	public static class VoidExpr extends Expr {
		public VoidExpr(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			if(type != null)
				return "(VoidExpr " + type.toString() + ")";
			else
				return "(VoidExpr)";
		}
	}
	public static class StructLiteralExpr extends Expr {
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
				return "(StructLiteralExpr " + getType() + identifier + ")";
			else {
				String str = "";
				for(Expr e : expressions)
					str += " " + e.toString();

				return "(StructLiteralExpr " + getType() + identifier + str + ")";
			}
		}
	}
	public static class DotExpr extends Expr {
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
			return "(DotExpr " + getType() + expr.toString() + " " + str + ")";
		}
	}
	public static class ArrayIndexExpr extends Expr {
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

				return "(ArrayIndexExpr " + getType() + expr.toString() + str + ")";
			}
		}
	}
	public static class CallExpr extends Expr {
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
				return "(CallExpr " + getType() + identifier + ")";
			else {
				String str = "";
				for(Expr e : expressions)
					str += " " + e.toString();

				return "(CallExpr " + getType() + identifier + str + ")";
			}
		}
	}

	public static class UnopExpr extends Expr {
		String op;
		Expr expr;

		public UnopExpr(int start_byte, int end_pos, String op, Expr expr) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.op = op;
			this.expr = expr;
		}

		@Override
		public String toString() {
			return "(UnopExpr " + getType() + op + " " + expr.toString() + ")";
		}
	}
	public static class BinopExpr extends Expr {
		String op;
		Expr expr1;
		Expr expr2;

		public BinopExpr(int start_byte, int end_pos, String op, Expr expr1, Expr expr2) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.op = op;
			this.expr1 = expr1;
			this.expr2 = expr2;
		}

		@Override
		public String toString() {
			return "(BinopExpr " + getType() + expr1.toString() + " " + op + " " + expr2.toString() + ")";
		}
	}
	public static class IfExpr extends Expr {
		Expr expr1;
		Expr expr2;
		Expr expr3;

		public IfExpr(int start_byte, int end_pos, Expr expr1, Expr expr2, Expr expr3) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.expr1 = expr1;
			this.expr2 = expr2;
			this.expr3 = expr3;
		}

		@Override
		public String toString() {
			return "(IfExpr " + getType() + expr1.toString() + " " + expr2.toString() + " " + expr3.toString() + ")";
		}
	}
	public static class ArrayLoopExpr extends Expr {
		List<String> variables;
		List<Expr> expressions;
		Expr expr;

		public ArrayLoopExpr(int start_byte, int end_pos, List<String> variables, List<Expr> expressions, Expr expr) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.variables = variables;
			this.expressions = expressions;
			this.expr = expr;
		}

		@Override
		public String toString() {
			if(expressions == null || expressions.size() == 0)
				return "(ArrayLoopExpr " + getType() + expr.toString() + ")";
			else {
				String str = "";
				for(int i = 0; i < expressions.size(); i++) {
					str += " " + variables.get(i) + " " + expressions.get(i).toString();
				}
				String t = getType();
				if(!t.equals("")) {
					t = t.substring(0, t.length() - 1); // remove extra space at the end
					t = " " + t;
				}
				return "(ArrayLoopExpr" + t + str + " " + expr.toString() + ")";
			}
		}
	}
	public static class SumLoopExpr extends Expr {

		List<String> variables;
		List<Expr> expressions;
		Expr expr;

		public SumLoopExpr(int start_byte, int end_pos, List<String> variables, List<Expr> expressions, Expr expr) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
			this.variables = variables;
			this.expressions = expressions;
			this.expr = expr;
		}

		@Override
		public String toString() {
			if(expressions == null || expressions.size() == 0)
				return "(SumLoopExpr " + getType() + expr.toString() + ")";
			else {
				String str = "";
				for(int i = 0; i < expressions.size(); i++) {
					str += " " + variables.get(i) + " " + expressions.get(i).toString();
				}
				String t = getType();
				if(!t.equals("")) {
					t = t.substring(0, t.length() - 1); // remove extra space at the end
					t = " " + t;
				}
				return "(SumLoopExpr" + t + str + " " + expr.toString() + ")";
			}
		}
	}

	public static class IntType extends Type {

		public IntType(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(IntType)";
		}
	}
	public static class FloatType extends Type {

		public FloatType(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(FloatType)";
		}
	}
	public static class BoolType extends Type {

		public BoolType(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(BoolType)";
		}
	}
	public static class VoidType extends Type {

		public VoidType(int start_byte, int end_pos) {
			this.start_byte = start_byte;
			this.end_pos = end_pos;
		}

		@Override
		public String toString() {
			return "(VoidType)";
		}
	}
	public static class StructType extends Type {
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
	public static class ArrayType extends Type {
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
		else if(type == Lexer.OperatorToken.class)
			return (((Lexer.OperatorToken)token).operator).equals(value);

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

	private enum PrecedenceLevel {
		// from highest to lowest precedence
		NONE, INDEXING, UNARY, MULTIPLICATIVE, ADDITIVE, COMPARISON, BOOLEAN, PREFIX
		// chain goes from right to left
		// calls it and all to the left of it
	}

	private static Expr parse_expr(List<Lexer.Token> tokens, int pos) throws ParserException {
		return parse_expr(tokens, pos, PrecedenceLevel.PREFIX); // lowest precedence to get all of chain
	}


	// continuously parse at a precedence level
	private static Expr parse_expr(List<Lexer.Token> tokens, int pos, PrecedenceLevel p) throws ParserException {
		Expr expr = parse_expr_base(tokens, pos, p); // parse starting at base LL1

		// throw it in until it's the same as last time
		while(true) {
			Expr expr_cont = parse_at_precedence(tokens, expr.end_pos, p, expr);
			if(expr_cont.toString().equals(expr.toString()))
				return expr;
			expr = expr_cont;
		}
	}

	private static Expr parse_at_precedence(List<Lexer.Token> tokens, int pos, PrecedenceLevel p, Expr expr) throws ParserException {
		switch(p) {
			case PREFIX -> { return parse_prefix(tokens, pos, expr); }
			case BOOLEAN -> { return parse_boolean(tokens, pos, expr); }
			case COMPARISON -> { return parse_comparison(tokens, pos, expr); }
			case ADDITIVE -> { return parse_additive(tokens, pos, expr); }
			case MULTIPLICATIVE -> { return parse_multiplicative(tokens, pos, expr); }
			case UNARY -> { return parse_unary(tokens, pos, expr); }
			case INDEXING -> { return parse_indexing(tokens, pos, expr); }
			case NONE -> { return expr; }
		}
		throw new ParserException("Unknown parsing error");
	}


	private static Expr parse_prefix(List<Lexer.Token> tokens, int pos, Expr lhs) throws ParserException {
		// start of chain (lowest precedence)
		// In case I have to move if/array/sum exprs here
		return parse_boolean(tokens, pos, lhs);
	}

	private static Expr parse_boolean(List<Lexer.Token> tokens, int pos, Expr lhs) throws ParserException {
		// binary operators: && ||
		if(lhs == null)
			return parse_comparison(tokens, pos, null);

		if(is_token(tokens, pos, Lexer.OperatorToken.class, "&&")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.COMPARISON);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "&&", lhs, rhs);
		}
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "||")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.COMPARISON);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "||", lhs, rhs);
		}

		return parse_comparison(tokens, pos, lhs);
	}

	private static Expr parse_comparison(List<Lexer.Token> tokens, int pos, Expr lhs) throws ParserException {
		// binary operators: >, <, <=, >=, ==, !=
		if(lhs == null)
			return parse_additive(tokens, pos, null);

		if(is_token(tokens, pos, Lexer.OperatorToken.class, ">")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.ADDITIVE);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, ">", lhs, rhs);
		}
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "<")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.ADDITIVE);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "<", lhs, rhs);
		}
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "<=")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.ADDITIVE);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "<=", lhs, rhs);
		}
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, ">=")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.ADDITIVE);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, ">=", lhs, rhs);
		}
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "==")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.ADDITIVE);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "==", lhs, rhs);
		}
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "!=")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.ADDITIVE);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "!=", lhs, rhs);
		}

		return parse_additive(tokens, pos, lhs);
	}

	private static Expr parse_additive(List<Lexer.Token> tokens, int pos, Expr lhs) throws ParserException {
		// binary operators: + -
		if(lhs == null)
			return parse_multiplicative(tokens, pos, null);

		if(is_token(tokens, pos, Lexer.OperatorToken.class, "+")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.MULTIPLICATIVE);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "+", lhs, rhs);
		}
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "-")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.MULTIPLICATIVE);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "-", lhs, rhs);
		}

		return parse_multiplicative(tokens, pos, lhs);
	}

	private static Expr parse_multiplicative(List<Lexer.Token> tokens, int pos, Expr lhs) throws ParserException {
		// operators: * / %
		if(lhs == null)
			return parse_unary(tokens, pos, null);

		if(is_token(tokens, pos, Lexer.OperatorToken.class, "*")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.UNARY);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "*", lhs, rhs);
		}
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "/")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.UNARY);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "/", lhs, rhs);
		}
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "%")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.UNARY);
			return new BinopExpr(lhs.start_byte, rhs.end_pos, "%", lhs, rhs);
		}

		return parse_unary(tokens, pos, lhs);
	}

	private static Expr parse_unary(List<Lexer.Token> tokens, int pos, Expr lhs) throws ParserException {
		// In case I have to move !/- unary exprs here
		// null is the special case for unary
		/*
		if(lhs == null && is_token(tokens, pos, Lexer.OperatorToken.class, "!")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.INDEXING);
			return new UnopExpr(tokens.get(pos-1).start_byte, rhs.end_pos, "!", rhs);
		}
		else if(lhs == null && is_token(tokens, pos, Lexer.OperatorToken.class, "-")) {
			pos++;
			Expr rhs = parse_expr(tokens, pos, PrecedenceLevel.INDEXING);
			return new UnopExpr(tokens.get(pos-1).start_byte, rhs.end_pos, "-", rhs);
		}*/

		return parse_indexing(tokens, pos, lhs);
	}

	private static Expr parse_indexing(List<Lexer.Token> tokens, int pos, Expr lhs) throws ParserException {
		if(lhs == null)
			return parse_expr_base(tokens, pos, PrecedenceLevel.NONE);

		// <exp>.<variable>
		if(is_token(tokens, pos, Lexer.PunctuationToken.class, ".")) {
			pos++;
			String identifier = expect_token(tokens, pos++, Lexer.IdentifierToken.class);
			return new DotExpr(lhs.start_byte, pos, lhs, identifier);
		}
		// <expr> [<expr, ...]
		else if(is_token(tokens, pos, Lexer.PunctuationToken.class, "[")) {
			pos++;
			List<Expr> expressions = parse_expr_chain(tokens, pos, "]");
			int end_pos = expressions.size() > 0 ?
					expressions.get(expressions.size() - 1).end_pos : pos;
			end_pos++;
			return new ArrayIndexExpr(lhs.start_byte, end_pos, lhs, expressions);
		}

		return lhs;
	}

	// starts at precedence p and goes to end, then restarts at the beginning.
	// necessary because at the end of some precedence's operation (...) there may more of the same precedence
	// or lower (x*y)+.. must check that it is same or higher before checking if lower.



	// each precedence level is chained, i.e. prefix calls boolean which calls comparison ...
	// get me the expression if its at precedence p or higher. I.e. ignore lower precedence levels
	// lhs_orig is a value that came back like "2" or "true"
	private static Expr parse_expr_base(List<Lexer.Token> tokens, int pos, PrecedenceLevel p) throws ParserException {
		Expr expr; // lhs

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
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "if"))
			expr = parse_ifexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "array"))
			expr = parse_arrayloopexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.KeywordToken.class, "sum"))
			expr = parse_sumloopexpr(tokens, pos);
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "-"))
			expr = parse_minus(tokens, pos);
		else if(is_token(tokens, pos, Lexer.OperatorToken.class, "!"))
			expr = parse_negate(tokens, pos);
		else throw new ParserException("Expected expr at byte:" + tokens.get(pos).start_byte);

		if(expr != null)
			pos = expr.end_pos;

		return parse_at_precedence(tokens, pos, p, expr);
	}

	private static Expr parse_minus(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		pos++; // was at '-' now one forward
		Expr expr = parse_expr(tokens, pos, PrecedenceLevel.INDEXING);

		//covert number here for some reason (was in the class compiler)
		/*
		var type = expr.getClass();
		if(type == Parser.IntExpr.class) {
			((IntExpr)expr).integer *= -1;
			return expr;
		}*/

		return new UnopExpr(tokens.get(start_pos).start_byte, expr.end_pos, "-", expr);
	}

	private static Expr parse_negate(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		pos++; // was at '!' now one forward
		Expr expr = parse_expr(tokens, pos, PrecedenceLevel.INDEXING);
		return new UnopExpr(tokens.get(start_pos).start_byte, expr.end_pos, "!", expr);
	}

	private static Expr parse_ifexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "if");
		Expr expr1 = parse_expr(tokens, pos);
		pos = expr1.end_pos;

		expect_keyword(tokens, pos++, "then");
		Expr expr2 = parse_expr(tokens, pos);
		pos = expr2.end_pos;

		expect_keyword(tokens, pos++, "else");
		Expr expr3 = parse_expr(tokens, pos);
		pos = expr3.end_pos;

		return new IfExpr(tokens.get(start_pos).start_byte, pos, expr1, expr2, expr3);
	}

	private static Expr parse_arrayloopexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "array");
		expect_punctuation(tokens, pos++, '[');

		List<String> variables = new ArrayList<>();
		List<Expr> expressions = new ArrayList<>();

		if(is_token(tokens, pos, Lexer.PunctuationToken.class, "]")) {
			pos++;
			Expr final_expr = parse_expr(tokens, pos);
			pos = final_expr.end_pos;
			return new ArrayLoopExpr(tokens.get(start_pos).start_byte, pos, variables, expressions, final_expr);
		}

		while(pos < tokens.size()) {
			String var = expect_token(tokens, pos++, Lexer.IdentifierToken.class);
			expect_punctuation(tokens, pos++, ':');
			Expr expr = parse_expr(tokens, pos);
			pos = expr.end_pos;

			variables.add(var);
			expressions.add(expr);

			if(is_token(tokens, pos, Lexer.PunctuationToken.class, "]")) {
				pos++;
				Expr final_expr = parse_expr(tokens, pos);
				pos = final_expr.end_pos;
				return new ArrayLoopExpr(tokens.get(start_pos).start_byte, pos, variables, expressions, final_expr);
			}
			else
				expect_punctuation(tokens, pos++, ',');
		}

		throw new ParserException("Expected closing brace at byte:" + tokens.get(pos).start_byte);
	}

	private static Expr parse_sumloopexpr(List<Lexer.Token> tokens, int pos) throws ParserException {
		int start_pos = pos;
		expect_keyword(tokens, pos++, "sum");
		expect_punctuation(tokens, pos++, '[');

		List<String> variables = new ArrayList<>();
		List<Expr> expressions = new ArrayList<>();

		if(is_token(tokens, pos, Lexer.PunctuationToken.class, "]")) {
			pos++;
			Expr final_expr = parse_expr(tokens, pos);
			pos = final_expr.end_pos;
			return new SumLoopExpr(tokens.get(start_pos).start_byte, pos, variables, expressions, final_expr);
		}

		while(pos < tokens.size()) {
			String var = expect_token(tokens, pos++, Lexer.IdentifierToken.class);
			expect_punctuation(tokens, pos++, ':');
			Expr expr = parse_expr(tokens, pos);
			pos = expr.end_pos;

			variables.add(var);
			expressions.add(expr);

			if(is_token(tokens, pos, Lexer.PunctuationToken.class, "]")) {
				pos++;
				Expr final_expr = parse_expr(tokens, pos);
				pos = final_expr.end_pos;
				return new SumLoopExpr(tokens.get(start_pos).start_byte, pos, variables, expressions, final_expr);
			}
			else
				expect_punctuation(tokens, pos++, ',');
		}

		throw new ParserException("Expected closing brace at byte:" + tokens.get(pos).start_byte);
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