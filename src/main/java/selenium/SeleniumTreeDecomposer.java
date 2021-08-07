package selenium;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;


public class SeleniumTreeDecomposer {
	//Delimiter generated from SeleniumIDE Extension
	private static final String DELIMITER_PO_DECLARATION = "System.out.println(\"{SeleniumIDEExt}";
	private static final String DELIMITER_BACK_TO_MAIN = DELIMITER_PO_DECLARATION+"backToMain\");";
	//Key for share data between methods
	private static final String KEY_HASH_PO_NAME = "pageObjectName";
	private static final String KEY_HASH_PO_METHOD = "pageMethodName";
	//Standard prefix for all the PO Object
	private static final String PO_PREFIX = "PO_";
	//List of base Imports
	private List<ImportDeclaration> baseImports = new LinkedList<>();
	//Base Package name
	private final String basePackage = "TestCases";
	//List of all CompilationUnit (alias Java File)
	private final List<CompilationUnit> units = new LinkedList<>();
	//Main Compilation Unit
	private final CompilationUnit centralUnit;
	//Main Class for write all the Test Method
	private final ClassOrInterfaceDeclaration centralClass;
	//List of Logs
	private final List<String> logs = new LinkedList<>();
	
	public SeleniumTreeDecomposer() {		
		centralUnit = new CompilationUnit();	
		centralClass = createClass(centralUnit,basePackage);			
		units.add(centralUnit);
	}
	
	/** Returns all the compilation unit created
	 * 
	 * @return units
	 */
	public List<CompilationUnit> getUnits() {		
		return units;
	}

	
	/** Starting Point to analyze a Java File
	 * For each import in this file, if the import wasn't declared in the Main Compilation unit, this import will be added
	 * Also because the import is needed for the test method, this import will be added to the PageObject class
	 * @param unitToAnalyze
	 */
	public void analyzeCompilationUnit(CompilationUnit unitToAnalyze) {
		List<ImportDeclaration> imports = unitToAnalyze.findAll(ImportDeclaration.class);
		for(ImportDeclaration importDecl : imports) {
			if(baseImports.contains(importDecl)) continue;
			baseImports.add(importDecl);
			addImport(centralUnit,importDecl);
		}
 		//Search for all the Class declaration in the files
 		for(ClassOrInterfaceDeclaration classToAnalyze : unitToAnalyze.findAll(ClassOrInterfaceDeclaration.class))
 			analyzeClass(classToAnalyze); 		 		
 	}
	

	/** Analyze the class searching for Field Declaration, Method Declaration
	 * Because the class will return itself, the class analysis is skipped, the main operation is to analyze only Field Declaration and MethodDeclaration
	 * Other operation can cause the IllegalArgumentException with the class type that has generated
	 * @param classToAnalyze
	 */
	private void analyzeClass(ClassOrInterfaceDeclaration classToAnalyze) {
		for(BodyDeclaration<?> declaration : classToAnalyze.findAll(BodyDeclaration.class)) {
			if(declaration.isFieldDeclaration()) {				
				addFieldDeclaration(declaration.asFieldDeclaration(),centralClass);
			}else if(declaration.isMethodDeclaration()) {
				analyzeMethod(declaration.asMethodDeclaration());				
			}else if(declaration.isClassOrInterfaceDeclaration()){
				//With this i can skip the Class or Interface declaration check
			}else {			
				throw new IllegalArgumentException("Cannot analyze this class: "+ classToAnalyze.getClass());				
			}			
		}		
	}
	
	/** add the field declaration to the class, only if the field isn't already declared
	 * if the field is already declared the method won't do anything
	 * else the method will add a new member to the class
	 * 
	 * @param declaration
	 * @param classDeclaration
	 */
	private void addFieldDeclaration(FieldDeclaration declaration, ClassOrInterfaceDeclaration classDeclaration) {
		declaration.setPrivate(true);
		for(FieldDeclaration field : classDeclaration.findAll(FieldDeclaration.class))
			if(field.hashCode()==declaration.hashCode()) //Field already in
				return;
		
		classDeclaration.addMember(declaration);		
	}

	/** Analyzing the method we can have two situation, the initializer method (tearDown/setUp) or a different name for the method
	 * If it is the default initializer, this will always go to the centralClass declaration
	 * else the body is needed to be read to check for each of the instruction	 * 
	 * @param method
	 */
	private void analyzeMethod(MethodDeclaration method) {
		if("setUp".equals(method.getNameAsString()) || "tearDown".equals(method.getNameAsString())) {
			//Add the method to the central class without parameter/arguments
			addMethod(method,centralClass,null,null);			
		}else {	
			//Read the body of the statement
			Optional<BlockStmt> bodyStmt = method.findFirst(BlockStmt.class);			
			if(!bodyStmt.isPresent())
				return;
			//If the body is present then get all the annotation and create a new Method with the same name and the Public modifier
			List<AnnotationExpr> annotations = method.getAnnotations();
			MethodDeclaration newMethod = centralClass.addMethod(method.getNameAsString(), Modifier.Keyword.PUBLIC);
			for(AnnotationExpr annotation : annotations)
				newMethod.addAnnotation(annotation);
			//Then analyze all the instruction present in the body
			analyzeInstructionCalls(newMethod,bodyStmt);
		}		
	}
	
	
	/** Analyze each instruction, to divide between TestMethod calls and PageObject calls
	 *  
	 * @param methodTestSuite
	 * @param blockStmt
	 */
	private void analyzeInstructionCalls(MethodDeclaration methodTestSuite, Optional<BlockStmt> blockStmt) {
		//This will hold the last page object found
		ClassOrInterfaceDeclaration lastPageObject = null;
		//This will hold the Method where the statement is added, could be a TestSuite method or a PageObject Method
		MethodDeclaration methodToAddStatement = methodTestSuite;
		//In the Key we can found the pageObject name, and in the value the name of the variable
		Map<String,String> localFieldDeclaration = new HashMap<>();
		//List for each values to be called in the method call declaration
		List<Node> values = new LinkedList<>();
		//List for each argument in the Method Declaration
		List<NameExpr> arguments = new LinkedList<NameExpr>();
		
		for(Node node : blockStmt.get().getChildNodes()) {
			
			Map<String,String> delimiterFound = checkDelimiterInstruction(node);
			boolean delimiterEnd = false;
			
			if(delimiterFound==null) 
				delimiterEnd = _checkDelimiterEnd(node);
			//If there is a delimiter
			if(delimiterFound!=null || delimiterEnd ) {
				//if a pageObject is found, the create the pageObject calls
				if(lastPageObject!=null)
					addPageObjectCall(methodTestSuite, lastPageObject, methodToAddStatement, localFieldDeclaration.get(lastPageObject.getNameAsString()),values,arguments);					
				//Reset the argument/values list
				values.clear();
				arguments.clear();
				//if the instruction is to go back to the write to the main Test Method
				//clear the lastPageObject and change the method where to add statement
				if(delimiterEnd) {					
					lastPageObject = null;
					methodToAddStatement = methodTestSuite;
					continue;
				}
				//If a delimiter is found, get the pageObject Name
				String pageObjectName = delimiterFound.get(KEY_HASH_PO_NAME);
				//recover the pageObject from the class
				lastPageObject = getPageObject(pageObjectName);
				//if the pageObject is not found, then should be created
				if(lastPageObject==null) 
					lastPageObject = createPageObject(pageObjectName);
				//if the local variable inside the TestMethod isn't declared, then the declaration must be added
				if(!localFieldDeclaration.containsKey(pageObjectName)) {
					//The value represent the Variable name, using an _ to be better identified inside the code 
					localFieldDeclaration.put(pageObjectName, "_"+pageObjectName);
					//The initialization of the PageObject is always formed by the 3 variables driver,var,js 
					methodTestSuite.getBody().get()
						.addStatement(pageObjectName+ " "+localFieldDeclaration.get(pageObjectName) +" = new "+pageObjectName+"(driver,vars,js);");
				}
				//Now a new void method is created with the PageMethodName.
				//if the method will need a different return statement, this will be change in a second time
				methodToAddStatement = new MethodDeclaration() 
					.setType("void")
					.setName(delimiterFound.get(KEY_HASH_PO_METHOD))
					.setPublic(true);
				continue; //Don't add the System.out.println
				
			}
			//Because each node is linked with another node, if the node isn't cloned the program will crash because the structure that holds the node is not modifiable
			Node clonedNode = node.clone();
			
			BlockStmt bodyMethod = methodToAddStatement.getBody().get();
			if(clonedNode instanceof ExpressionStmt) { //2 option, is Assert or normal command
				ExpressionStmt expStmt = (ExpressionStmt)clonedNode;
				//if the pageObject isn't already found, then there is no need to check for arguments
				if(lastPageObject!=null)  				
					analyzeMethodArguments(expStmt,values,arguments);			
				//If the statement doesn't start with assert means that it is a normal call, also the pageObject needed to be inizialited
				if(expStmt.toString().startsWith("assert") && lastPageObject!=null) {
					//TODO add call to this method declaration
					//TODO create a new Getter for this assert
					analyzeAssertCallExpStmt(
							methodTestSuite, 
							lastPageObject,
							localFieldDeclaration.get(lastPageObject.getNameAsString()), 
							methodToAddStatement,
							bodyMethod,
							expStmt,
							values,
							arguments);	
					//Return to write in the main statement as default
					lastPageObject = null;
					methodToAddStatement = methodTestSuite;		
				}
				else { 
					//if it's not a special statement (assert), just add it to the bodyMethod of the TestMethod or PageObject Method
					bodyMethod.addStatement(expStmt);	
				}
			}else if (clonedNode instanceof BlockStmt) { //2 option, can contain Assert or normal block
				BlockStmt blockInstruction = (BlockStmt) clonedNode;				
				if(lastPageObject==null) { //No pageObject, so i don't care what contains or there isn't an assert call				
					bodyMethod.addAndGetStatement(blockInstruction);
				} else if(searchForAssertInBlockStmt(blockInstruction)) {
					analyzeAssertCallBlockStmt(methodTestSuite,lastPageObject, localFieldDeclaration.get(lastPageObject.getNameAsString()), methodToAddStatement,
							bodyMethod, blockInstruction,values,arguments);				
					lastPageObject = null;
					methodToAddStatement = methodTestSuite;
				}else {					
					BlockStmt blockParsed = new BlockStmt();
					bodyMethod.addAndGetStatement(blockParsed);
					for(Node child : blockInstruction.getChildNodes()) {
						ExpressionStmt expStmt = (ExpressionStmt)child.clone();
						analyzeMethodArguments(expStmt,values,arguments);	
						blockParsed.addAndGetStatement(expStmt);
					}
					
				}		
			} else if (clonedNode instanceof Comment) {
				bodyMethod.addOrphanComment((Comment) clonedNode);
			}else 
				bodyMethod.addStatement((Statement) clonedNode);						
		}
		//after all the instruction end, if there is a PageObject declared than add is MethodCallDeclaration to the TestSuite Method
		if(lastPageObject!=null)				
			addPageObjectCall(methodTestSuite, lastPageObject, methodToAddStatement, localFieldDeclaration.get(lastPageObject.getNameAsString()),values,arguments);					
				
	}


	

	/** Search in all the command of the BlockStmt if there is a assert call
	 * return true if the assert call exist else false
	 * @param blockInstruction
	 * @return
	 */
	private boolean searchForAssertInBlockStmt(BlockStmt blockInstruction) {	
		for(Node child : blockInstruction.getChildNodes()) 
			if(child.toString().startsWith("assert")) 
				return true;					
		return false;
	}

	/** Add the pageObject Calls to the Test Method
	 * 
	 * @param methodTestSuite
	 * @param pageObject
	 * @param methodPO
	 * @param pageObjectVariable
	 * @param values
	 * @param argument
	 */
	private void addPageObjectCall(MethodDeclaration methodTestSuite, ClassOrInterfaceDeclaration pageObject,
			MethodDeclaration methodPO, String pageObjectVariable,
			List<Node> values, 
			List<NameExpr> argument) {		
		MethodDeclaration methodAdded = addMethod( methodPO,  pageObject,values,argument);
		addCallToMethod(pageObjectVariable,methodTestSuite,methodAdded,values);
	}



	/** Search in all the Compilation unit if the pageObject is already declared
	 * if it is already declared will return the pageObject class 
	 * else null
	 * 
	 * @param pageObject
	 * @return
	 */
	private ClassOrInterfaceDeclaration getPageObject(String pageObject) {
		for(CompilationUnit unit : units)
			for(ClassOrInterfaceDeclaration searchedClass : unit.findAll(ClassOrInterfaceDeclaration.class)) 		
				if(searchedClass.getNameAsString().equals(pageObject))
					return searchedClass;		
		return null;
	}
	
	/** Create a new Page Object Class with the default initialization
	 * This method will also create a new CompilationUnit that will be added to the list of all the compilation unit
	 * 
	 * @param pageObject
	 * @return
	 */	
	private ClassOrInterfaceDeclaration createPageObject(String pageObject) {
		Map<String,String> hashMap = new HashMap<>();
		hashMap.put("WebDriver", "driver");
		hashMap.put("Map<String,Object>", "vars");
		hashMap.put("JavascriptExecutor", "js");		
		CompilationUnit unit = new CompilationUnit();
		units.add(unit);
		addImports(unit,baseImports);		
		ClassOrInterfaceDeclaration classCreated = createClass(unit,pageObject);
				
		for(Map.Entry<String,String> entry : hashMap.entrySet()) {
			classCreated.addField(entry.getKey(), entry.getValue());			
		}		
		
		ConstructorDeclaration constructor = classCreated.addConstructor().setPublic(true);
		BlockStmt blockStmt = new BlockStmt();
		
		for(Map.Entry<String,String> entry : hashMap.entrySet()) {			
			constructor.addParameter(entry.getKey(), entry.getValue());
			blockStmt.addStatement("this."+entry.getValue()+"="+entry.getValue()+";");
		}		
		constructor.setBody(blockStmt);
		return classCreated;
	}
	
	/** Create a MethodCallDeclaration with the list of values 
	 * 
	 * @param variableName
	 * @param methodWhoCalls
	 * @param methodToCall
	 * @param values
	 */
	private void addCallToMethod(String variableName, MethodDeclaration methodWhoCalls, MethodDeclaration methodToCall,List<Node> values) {
		String statement = variableName+"."+methodToCall.getNameAsString()+"(";
		statement+= argumentParser(values);
		statement+=");";		
		methodWhoCalls.getBody().get().addStatement(statement);		
	}




	/* Utility Method for JavaParser */

 	/**Add package to a Compilation unit
 	 * If the package is not the CentralUnit, then add it's reference to the central unit 
 	 * as import
 	 * 
 	 * @param unit
 	 * @param packageName 	
 	 */
 	private void addPackage(CompilationUnit unit, String packageName) {
		unit.setPackageDeclaration(packageName);
		/* This will add the import only if it's not the CentralUnit, but because there should be a bug, it won't add the Import after the class declaration*/
		if(unit!=this.centralUnit) 	{				
			addImport(this.centralUnit, new	ImportDeclaration(packageName,false,true));	
		}
		
	}
	
	/**Add multiple import to a compilation unit
	 * 
	 * @param unit
	 * @param importElements
	 */
 	private void addImports(CompilationUnit unit, List<ImportDeclaration> importElements) {
		for(ImportDeclaration importElement : importElements)
			addImport(unit,importElement);	
	}
 	
	/**Add a single import to a compilation unit
	 * 
	 * @param unit
	 * @param importEl
	 */
 	private void addImport(CompilationUnit unit,ImportDeclaration importEl){
		unit.addImport(importEl);
	}
 	
	/** The method will create a new Class if and only if there isn't a Class with the same name in the entire list of compilation unit
	 * If the class doesn't exist the class is created and a Package is assigned and all the imports that is present in the TestCases
	 * If the class already exist in the CompilationUnit list, the class object is returned
	 * 	
	 * @param unit
	 * @param className
	 * @return 
	 */
 	private ClassOrInterfaceDeclaration createClass(CompilationUnit unit,String className) {
		for(ClassOrInterfaceDeclaration searchedClass : unit.findAll(ClassOrInterfaceDeclaration.class)) 		
			if(searchedClass.getNameAsString().equals(className))
				return searchedClass;
		
		ClassOrInterfaceDeclaration newClass = unit.addClass(className).setPublic(true);
		boolean isNotCentralPackage = centralUnit!=unit;
		String packageName = basePackage;
		if(isNotCentralPackage) {
			packageName+=".PO";			
		}
		addPackage(unit,packageName);
		return newClass;
	}	
 	
 	
 	/** The method is modified and the list of parameters is added in the declaration
 	 * If the method already exist in the class, then the method is returned
 	 * else the method is added to the method list of the class. If there is another method with the same name but different body
 	 * then the method is renamed with a progressive from 1 to N
 	 * 
 	 * @param methodToAdd
 	 * @param addToClass
 	 * @param argTypes
 	 * @param argName
 	 * @return
 	 */
	private MethodDeclaration addMethod(MethodDeclaration methodToAdd, ClassOrInterfaceDeclaration addToClass,List<Node> argTypes, List<NameExpr> argName) {
		methodAddArguments(methodToAdd,argTypes,argName);
		MethodDeclaration alreadyInMethod = getMethodAlreadyIn(methodToAdd,addToClass);
		if(alreadyInMethod!=null)
			return alreadyInMethod;
		int index = 1;
		String baseMethodName = methodToAdd.getNameAsString();
		while(methodSameName(methodToAdd,addToClass)) {
			methodToAdd.setName(baseMethodName+"_"+index);
			index++;
		}
		addToClass.addMember(methodToAdd);	
		return methodToAdd;
	}
	
	/** If there is a list of argTypes, then the method is modified and for each argTypes a new Parameter is added
	 * because all the parameters is of types String, this is hard-coded. But the Node with he real values can be read in the argTypes list
	 * 
	 * @param method
	 * @param argTypes
	 * @param argName
	 */
	private void methodAddArguments(MethodDeclaration method, List<Node> argTypes, List<NameExpr> argName) {
		if(argTypes==null) return;
		for(int i=0;i<argTypes.size();i++) {
			//Node value = argTypes.get(i); //if i want to parse other type of Input
			NameExpr nameVariable = argName.get(i);
			Parameter parameter = new Parameter();
			parameter.setType("String");		
			parameter.setName(nameVariable.toString());
			method.addAndGetParameter(parameter);
		}
	}

	/** Search if the method is already in the class.
	 * If the method already exist and have the same list of parameter,name, body the method already created is returned
	 * if the method has the same list of parameter and body, but with a different name, a warning is added to the log, and the method is returned
	 * Else null is returned that indicates the method is new and should be added to the class
	 * @param methodToSearch
	 * @param classToSearch
	 * @return
	 */
	private MethodDeclaration getMethodAlreadyIn(MethodDeclaration methodToSearch, ClassOrInterfaceDeclaration classToSearch) {
		List<MethodDeclaration> methods = classToSearch.findAll(MethodDeclaration.class);
		for(MethodDeclaration method : methods) { 
			if(method.hashCode()==methodToSearch.hashCode()) 				
				return method;			
					
			if(!method.getParameters().toString().equals(methodToSearch.getParameters().toString())) 			
				continue;
			
			String bodyStatement = method.getBody().get().toString();
			
			if(bodyStatement.equals(methodToSearch.getBody().get().toString())) {	
				if(!method.getNameAsString().equals(methodToSearch.getNameAsString())) {
					String unified = method.getNameAsString();
					addWarning("For PO:" +classToSearch.getNameAsString()+" method "+methodToSearch.getNameAsString() +" and "+unified+" unified under the name "+unified+" since bodies and paramters list are identical");
				}
				return method;
			}			
		}
		return null;
	}
	
	/** This will check if there is a method with the same name
	 * It doens't check if the method is the same or anything else but just if there is a same method name 
	 * @param methodToAdd
	 * @param addToClass
	 * @return
	 */
	private boolean methodSameName(MethodDeclaration methodToAdd, ClassOrInterfaceDeclaration addToClass) {
		List<MethodDeclaration> methods = addToClass.findAll(MethodDeclaration.class);
		for(MethodDeclaration method : methods) 
			if(method.getNameAsString().equals(methodToAdd.getNameAsString())) 
				return true;		
		return false;
	}
	
	/*Assert Call Analyzer */
	
	/** This method will analyze a Single Assert instruction
	 *  
	 * @param methodTestSuite
	 * @param pageObjectVariable
	 * @param methodPO
	 * @param bodyMethod
	 * @param expression
	 * @param values
	 * @param argumentsName
	 */
	private void analyzeAssertCallExpStmt(MethodDeclaration methodTestSuite, ClassOrInterfaceDeclaration pageObject, String pageObjectVariable,
			MethodDeclaration methodPO, BlockStmt bodyMethod,
			ExpressionStmt expression,
			List<Node> values,
			List<NameExpr> argumentsName) {
		MethodCallExpr assertCall = (MethodCallExpr) expression.getExpression();
		List<Node> childNodes = assertCall.getChildNodes(); 
		//Node 0 is the commnad
		//Node 1 is the Locator
		//Node 2 is the optional value to check			
		switch(childNodes.get(0).toString()) {
			case "assertEquals":
			case "assertThat":							
				methodPO.setType("String");
				break;
			case "assertTrue":
			case "assertFalse":							
				methodPO.setType("boolean");
				break;
			default:						
				throw new UnsupportedOperationException("No conversion found for this istruction: " +childNodes.get(0).toString() );					
		}												
		MethodCallExpr methodCall = (MethodCallExpr) childNodes.get(1);
		bodyMethod.addStatement("return " + methodCall+";");	
		//Add Method To PO
		addMethod(methodPO,pageObject,values,argumentsName);	
		//Now add the assert in the Main Function	
		bodyMethod = methodTestSuite.getBody().get();	
		//Create the statement
		String statement = createAssertWithNormalStmt(childNodes,pageObjectVariable, methodPO, values);									
		bodyMethod.addStatement(statement);
		values.clear();
		argumentsName.clear();
	}

		
	/** This method will analyze a BlockStmt assert instruction
	 * 
	 * @param methodTestSuite
	 * @param lastPageVariable
	 * @param methodPO
	 * @param bodyMethod
	 * @param blockInstruction
	 * @param values
	 * @param argumentsName
	 */
	private void analyzeAssertCallBlockStmt(MethodDeclaration methodTestSuite,ClassOrInterfaceDeclaration pageObject,
			String lastPageVariable, MethodDeclaration methodPO,
			BlockStmt bodyMethod, BlockStmt blockInstruction,List<Node> values,List<NameExpr> argumentsName) {
		BlockStmt newBlockStmt = new BlockStmt();
		bodyMethod.addAndGetStatement(newBlockStmt);
		Node lastNode = null;
		for(Node child : blockInstruction.getChildNodes()) {
			if(child.toString().startsWith("assert")) {				
				//TODO add call to this method declaration
				//TODO create a new Getter for this assert
				VariableDeclarationExpr variableDeclExp = (VariableDeclarationExpr) lastNode.getChildNodes().get(0);
				methodPO.setType(variableDeclExp.getElementType());
				VariableDeclarator variableDecl = (VariableDeclarator)variableDeclExp.getChildNodes().get(0);
				newBlockStmt.addStatement("return " +variableDecl.getNameAsString()+";");
				
				addMethod(methodPO,pageObject,values,argumentsName);
				String statement;
				if( child instanceof AssertStmt) { //per questo è un caso speciale
					statement = createAssertWithAssertStmt(lastPageVariable, methodPO, values, child, variableDecl);	
				}else {
					List<Node> childNodes = ((MethodCallExpr)((ExpressionStmt)child).getExpression()).getChildNodes(); 
					statement = createAssertWithNormalStmt(childNodes,lastPageVariable, methodPO, values);		
				}	
				bodyMethod = methodTestSuite.getBody().get();
				bodyMethod.addStatement(statement);
			}else {
				ExpressionStmt expStmt = (ExpressionStmt)child.clone();
				analyzeMethodArguments(expStmt,values,argumentsName);	
				newBlockStmt.addStatement(expStmt);
			}
			lastNode = child;
		}
		values.clear();
		argumentsName.clear();
	}

	private String createAssertWithNormalStmt(List<Node> childNodes,String lastPageVariable, MethodDeclaration methodPO, List<Node> values) {
		String statement = childNodes.get(0).toString() +"(";		
		statement+=lastPageVariable+"."+methodPO.getNameAsString()+"(";
		statement+=argumentParser(values);	
		statement+=")";
		for(int i=2;i<childNodes.size();i++)
			statement+=","+childNodes.get(i);					
		statement+=");";
		return statement;
	}

	private String createAssertWithAssertStmt(String lastPageVariable, MethodDeclaration methodPO, List<Node> values,
			Node child, VariableDeclarator variableDecl) {
		String statement;
		AssertStmt asserStmt = (AssertStmt) child;
		EnclosedExpr enclosedExp = (EnclosedExpr) asserStmt.getChildNodes().get(0);
		BinaryExpr binaryExpr = (BinaryExpr) enclosedExp.getChildNodes().get(0);
		statement = "assert("; 					
		statement+=lastPageVariable+"."+methodPO.getNameAsString()+"(";
		statement+=argumentParser(values);					
		statement+=")";
		statement+= binaryExpr.getLeft().toString().replaceFirst(variableDecl.getNameAsString(), "");
		statement+=" "+binaryExpr.getOperator().asString()+" "+binaryExpr.getRight()+");";
		return statement;
	}
	
	/* Delimiter Searcher*/
	/** Check if the instruction {SeleniumIDEExt}backToMain is found
	 * @param exp
	 * @return 
	 */
	private boolean _checkDelimiterEnd(Node exp) {
		return DELIMITER_BACK_TO_MAIN.equals(exp.toString()); 
	}
	
	/** Check if the instruction is a System.out.println(\"{SeleniumIDEExt} Delimiter
	 *  
	 * @param exp
	 * @return null if it's not a delimiter, otherwise will return the map of the elements pageObject/pageMethod
	 */
	private Map<String, String> checkDelimiterInstruction(Node exp) {
		if(!exp.toString().contains(DELIMITER_PO_DECLARATION)) 
			return null;
		Map<String,String> hashMap = new HashMap<>();
		Optional<MethodCallExpr> callExp = exp.findFirst(MethodCallExpr.class);
		if(callExp.isEmpty()) return null;
		
		LiteralExpr argument = callExp.get().getArgument(0).asLiteralExpr();
		String[] values = argument.toString().replaceAll("\"","").split(":");
		if(values.length!=3) 
			return null;
		hashMap.put(KEY_HASH_PO_NAME, PO_PREFIX+values[1].toLowerCase());
		hashMap.put(KEY_HASH_PO_METHOD, values[2]);		
		return hashMap;
	}
	
	
	/** Argument Analyzer */
	
	/** This method will create a String with all the argument to use to call a Mehtod
	 * 
	 * @param values
	 * @return "" or a string of arguments call
	 */
	private String argumentParser(List<Node> values) {
		String statement = "";
		if(values!=null && values.size()>0) {
			statement+=values.get(0).toString();
			for(int i=1;i<values.size();i++) 
				statement+=","+values.get(i).toString();			
		}		
		return statement;
	}


	/** For each ExpressionStmt will search if it's a MethodCallExpr that contains sendKeys or By.xpath
	 * In that case it will change the node inside the call and replace it with a variable
	 * Then will add this variable and the original value in memory for create the correct call and method invocation
	 * @param expStmt 
	 * @param values list of real values
	 * @param variables list of variable
	 */
	private void analyzeMethodArguments(ExpressionStmt expStmt,List<Node> values,List<NameExpr> variables) {
		
		if(!(expStmt.getChildNodes().get(0) instanceof MethodCallExpr)) 
			return;	
		
		boolean containsSendKeys = expStmt.toString().contains("sendKeys");
		boolean containsXPath  = expStmt.toString().contains("By.xpath");
		
		if(!containsSendKeys && !containsXPath) { 			
			return; //no sendKeys or Xpath means no argument to check
		}
		
		MethodCallExpr methodCall = (MethodCallExpr) expStmt.getChildNodes().get(0);
		
		List<Node> childs = methodCall.getChildNodes();	
		if(containsSendKeys) {
			extractArgumentFromSendKeys(childs,methodCall, values, variables  );
		}
		
		if(containsXPath) {				
			extractArgumentFromXPath((MethodCallExpr)childs.get(0),values, variables );
		}
		
	}
	
	/** Extract the argument from the sendKeys command
	 * 
	 * @param values
	 * @param variables
	 * @param methodCall
	 * @param childs
	 */
	private void extractArgumentFromSendKeys(List<Node> childs, MethodCallExpr methodCall,    List<Node> values, List<NameExpr> variables
			) {
		for(int i=0;i<childs.size();i++) {
			Node node = childs.get(i);
			if(node instanceof SimpleName) {
				SimpleName name = (SimpleName)node;
				if("sendKeys".equals(name.toString())) {
					//the next one is the argument
					i++;
					node = childs.get(i);	
					if(!(node instanceof StringLiteralExpr)) continue; //only text write on keyboard, no press key like Enter or else	
					values.add(childs.get(i));				
					//now i need to replace this value with a variable					
					NameExpr var = new NameExpr("key"+values.size());					
					variables.add(var);
					methodCall.replace(node,var);	
				}
			}
		}
	}
	
	/** Extract the argument from the By.xpath call
	 * 
	 * @param methodCallExpr
	 * @param values
	 * @param variables
	 */
	private void extractArgumentFromXPath(MethodCallExpr methodCallExpr, List<Node> values, List<NameExpr> variables)
	{	
		List<Node> childs = methodCallExpr.getChildNodes();
		for(int i=0;i<childs.size();i++) {
			Node node = childs.get(i);		
			if(node instanceof MethodCallExpr) {
				MethodCallExpr xPathCall = (MethodCallExpr)node;				
				if(xPathCall.toString().startsWith("By.xpath")) {
					//the child is: By, xpath, realPathString
					Node literal = xPathCall.getChildNodes().get(2);
					if(!(literal instanceof StringLiteralExpr)) continue; 				
					String[] optionSplit = literal.toString().split("'");
					values.add(new StringLiteralExpr(optionSplit[1]));	
					NameExpr var = new NameExpr("key"+values.size());
					variables.add(var);									
					MethodCallExpr newMethodCall = new MethodCallExpr("By.xpath");
					newMethodCall.addArgument(optionSplit[0]+"'\"+"+var.getNameAsString()+"+\"'"+optionSplit[2]);			
					methodCallExpr.replace(node,newMethodCall);					
				}				
			}			
		}
	}
	

	/* Logs */	
	/** Return all the logs that happen during the compilation
	 * 
	 * @return list of logs
	 */
	public List<String> getLogs() {
		return logs;
	}
	
	/** Create a Log of warning for something happen
	 * 
	 * @param log
	 */
	private void addWarning(String log) {
		addLog("Warning: " +log);
	}
	
	/** Add the log to the List of logs with the time stamp of when it happen
	 * 
	 * @param log
	 */	
	private void addLog(String log) {
		logs.add((new Date()).toLocaleString() +" - "+log);
	}

	public CompilationUnit getTestSuiteUnit() {		
		return centralUnit;
	}	
}
